package com.yupi.juziaiagent.limits;

import com.yupi.juziaiagent.agent.model.BaseAgent;
import com.yupi.juziaiagent.config.ModelLimitsProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** 所有聊天 HTTP 入口共享的任务限额；不排队等待并发名额。 */
@Component
public class ModelCallGuard implements DisposableBean {
    private final ModelLimitsProperties limits;
    private final LongSupplier clock;
    private final Deque<Long> accepted = new ArrayDeque<>();
    private final ExecutorService executor;
    private int active;

    @Autowired
    public ModelCallGuard(ModelLimitsProperties limits) {
        this(limits, System::nanoTime);
    }

    ModelCallGuard(ModelLimitsProperties limits, LongSupplier clock) {
        limits.afterPropertiesSet();
        this.limits = limits;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(limits.getMaxConcurrentTasks(), task -> {
            Thread thread = new Thread(task, "limited-chat-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void validate(String message) {
        if (message == null || message.isBlank()) {
            throw new ModelLimitException(HttpStatus.BAD_REQUEST, "请输入聊天内容。");
        }
        if (message.codePointCount(0, message.length()) > limits.getMaxInputChars()) {
            throw new ModelLimitException(HttpStatus.BAD_REQUEST,
                    "输入过长，单次最多允许 " + limits.getMaxInputChars() + " 个字符，请缩短后重试。");
        }
    }

    public synchronized Lease acquire(String message) {
        validate(message);
        long now = clock.getAsLong();
        long minute = TimeUnit.MINUTES.toNanos(1);
        while (!accepted.isEmpty() && now - accepted.peekFirst() >= minute) {
            accepted.removeFirst();
        }
        if (active >= limits.getMaxConcurrentTasks()) {
            throw new ModelLimitException(HttpStatus.TOO_MANY_REQUESTS, "正在处理的聊天任务较多，请等待当前任务结束后重试。", 1);
        }
        if (accepted.size() >= limits.getMaxTasksPerMinute()) {
            long remaining = minute - (now - accepted.peekFirst());
            int retryAfter = (int) Math.max(1, (remaining + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
            throw new ModelLimitException(HttpStatus.TOO_MANY_REQUESTS, "聊天请求过于频繁，请稍后重试。", retryAfter);
        }
        accepted.addLast(now);
        active++;
        return new Lease();
    }

    public <T> T call(String message, Supplier<T> action) {
        Lease lease = acquire(message);
        AtomicBoolean started = new AtomicBoolean();
        FutureTask<T> task = new FutureTask<>(() -> {
            try {
                return action.get();
            } finally {
                // 先释放正常退出的名额，再向等待者公布结果，避免紧接着重试误判忙碌。
                lease.close();
            }
        }) {
            @Override
            public void run() {
                started.set(true);
                try {
                    super.run();
                } finally {
                    // 超时只发出取消信号；名额必须等真实工作退出后再释放。
                    lease.close();
                }
            }

            @Override
            protected void done() {
                if (!started.get()) {
                    lease.close();
                }
            }
        };
        try {
            executor.execute(task);
        } catch (RuntimeException error) {
            lease.close();
            throw error;
        }
        try {
            return task.get(limits.getChatTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            throw timeout();
        } catch (InterruptedException error) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ModelLimitException(HttpStatus.SERVICE_UNAVAILABLE, "聊天任务已取消，请稍后重试。");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new IllegalStateException("聊天任务执行失败", error.getCause());
        }
    }

    public <T> Flux<T> stream(String message, Supplier<Flux<T>> action) {
        validate(message);
        // using 确保完成、报错和取消都释放名额；计时不随分片或心跳重置。
        return Flux.using(() -> acquire(message), lease -> {
            long deadline = System.nanoTime() + limits.getChatTimeout().toNanos();
            return Flux.defer(action::get).timeout(Mono.delay(limits.getChatTimeout()),
                    chunk -> Mono.delay(Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))),
                    Flux.error(timeout()));
        }, Lease::close);
    }

    public SseEmitter startAgent(String message, Supplier<? extends BaseAgent> factory) {
        Lease lease = acquire(message);
        try {
            return factory.get().runStream(message, limits.getManusTimeout().toMillis(), lease::close);
        } catch (RuntimeException | Error error) {
            lease.close();
            throw error;
        }
    }

    private ModelLimitException timeout() {
        return new ModelLimitException(HttpStatus.GATEWAY_TIMEOUT, "本次聊天已超过时间上限，请缩短问题后重试。");
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                synchronized (ModelCallGuard.this) {
                    active--;
                }
            }
        }
    }
}
