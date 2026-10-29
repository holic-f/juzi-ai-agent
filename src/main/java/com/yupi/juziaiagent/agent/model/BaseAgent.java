package com.yupi.juziaiagent.agent.model;

import com.itextpdf.styledxmlparser.jsoup.internal.StringUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.Map;
import java.util.function.Consumer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 *
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    private static final ObjectMapper EVENT_JSON = new ObjectMapper();
    private final String runId = UUID.randomUUID().toString().substring(0, 8);
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private volatile Consumer<String> answerSink;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean answerStreamed;

    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final ExecutorService AGENT_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "agent-stream");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledThreadPoolExecutor HEARTBEAT_EXECUTOR = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "agent-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private static final ScheduledThreadPoolExecutor DEADLINE_EXECUTOR = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "agent-deadline");
        thread.setDaemon(true);
        return thread;
    });

    static {
        HEARTBEAT_EXECUTOR.setRemoveOnCancelPolicy(true);
        DEADLINE_EXECUTOR.setRemoveOnCancelPolicy(true);
    }

    // 核心属性
    private String name;

    // 提示
    private String systemPrompt;
    private String nextStepPrompt;

    // 状态
    private volatile AgentState state = AgentState.IDLE;
    // 独立于业务状态：step() 可能在连接关闭后才返回并更新 state。
    private volatile boolean cancelled;

    // 执行控制
    private int maxSteps = 10;
    private int currentStep = 0;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private long runTimeoutMillis = TimeUnit.MINUTES.toMillis(15);

    // LLM
    private ChatClient chatClient;

    // Memory（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
//    public String run(String userPrompt) {
//        if (this.state != AgentState.IDLE) {
//            throw new RuntimeException("Cannot run agent from state: " + this.state);
//        }
//        if (StringUtil.isBlank(userPrompt)) {
//            throw new RuntimeException("Cannot run agent with empty user prompt");
//        }
//        // 更改状态
//        state = AgentState.RUNNING;
//        // 记录消息上下文
//        messageList.add(new UserMessage(userPrompt));
//        // 保存结果列表
//        List<String> results = new ArrayList<>();
//        try {
//            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
//                int stepNumber = i + 1;
//                currentStep = stepNumber;
//                log.info("Executing step " + stepNumber + "/" + maxSteps);
//                // 单步执行
//                String stepResult = step();
//                String result = "Step " + stepNumber + ": " + stepResult;
//                results.add(result);
//            }
//            // 检查是否超出步骤限制
//            if (currentStep >= maxSteps) {
//                state = AgentState.FINISHED;
//                results.add("Terminated: Reached max steps (" + maxSteps + ")");
//            }
//            return String.join("\n", results);
//        } catch (Exception e) {
//            state = AgentState.ERROR;
//            log.error("Error executing agent", e);
//            return "执行错误" + e.getMessage();
//        } finally {
//            // 清理资源
//            this.cleanup();
//        }
//    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter实例
     */
    public synchronized SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, TimeUnit.MINUTES.toMillis(15), () -> { });
    }

    /** 外部任务限额的名额只能在工作线程退出后释放。 */
    public synchronized SseEmitter runStream(String userPrompt, long timeoutMillis, Runnable onWorkerExit) {
        if (state != AgentState.IDLE) {
            throw new IllegalStateException("无法从状态运行代理: " + state);
        }
        if (StringUtil.isBlank(userPrompt)) {
            throw new IllegalArgumentException("不能使用空提示词运行代理");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("任务超时必须大于 0");
        }
        java.util.Objects.requireNonNull(onWorkerExit, "onWorkerExit");
        runTimeoutMillis = timeoutMillis;
        SseEmitter emitter = createEmitter();
        StreamSession session = new StreamSession(emitter);
        // 必须先注册回调，再启动后台任务，避免快速完成时遗漏生命周期事件。
        emitter.onTimeout(() -> {
            log.warn("SSE connection timed out");
            session.expire();
        });
        emitter.onError(error -> session.disconnect());
        emitter.onCompletion(session::disconnect);
        state = AgentState.RUNNING;
        long taskStarted = System.nanoTime();
        AGENT_EXECUTOR.execute(() -> {
            try {
                session.start();
                answerSink = session::delta;
                checkCancelled();
                messageList.add(new UserMessage(userPrompt));
                for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                    checkCancelled();
                    currentStep = i + 1;
                    log.info("agent_step run={} step={}/{}", runId, currentStep, maxSteps);
                    step();
                    checkCancelled();
                }
                if (state == AgentState.FINISHED) {
                    String finalAnswer = messageList.stream()
                            .filter(message -> message instanceof org.springframework.ai.chat.messages.AssistantMessage)
                            .map(message -> ((org.springframework.ai.chat.messages.AssistantMessage) message).getText())
                            .filter(answer -> answer != null && !answer.isBlank())
                            .reduce((first, last) -> last)
                            .orElse("未能生成最终答复");
                    session.finish(finalAnswer, false);
                } else {
                    String error = state == AgentState.RUNNING
                            ? "未能在最大步骤数内完成任务，请缩小任务范围后重试。"
                            : "智能体执行失败，请重试。";
                    state = AgentState.ERROR;
                    session.finish(error, true);
                }
            } catch (Exception e) {
                if (!cancelled) {
                    state = AgentState.ERROR;
                    log.error("执行智能体失败", e);
                    session.finish("智能体执行失败，请稍后重试。", true);
                }
            } finally {
                // 只在工作线程退出后清理一次，避免回调与工具执行并发清理资源。
                if (cancelled) {
                    state = AgentState.ERROR;
                }
                answerSink = null;
                session.stopHeartbeat();
                try {
                    cleanup();
                } finally {
                    try {
                        log.info("agent_task run={} state={} steps={} elapsed_ms={}", runId, state, currentStep,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - taskStarted));
                    } finally {
                        onWorkerExit.run();
                    }
                }
            }
        });
        return emitter;
    }

    /** 仅用于已确定为最终答复的内容；不能发送规划文本或工具日志。 */
    protected void emitAnswerDelta(String text) {
        checkCancelled();
        if (text == null || text.isEmpty()) {
            return;
        }
        Consumer<String> sink = answerSink;
        if (sink != null) {
            sink.accept(text);
            answerStreamed = true;
        }
        checkCancelled();
    }

    protected void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("SSE connection closed");
        }
    }

    protected SseEmitter createEmitter() {
        // 心跳只避免空闲断流，总执行时间仍有独立上限。
        return new SseEmitter(runTimeoutMillis + 1000) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {
                super.extendResponse(response);
                response.getHeaders().setContentType(
                        new MediaType("text", "event-stream", StandardCharsets.UTF_8));
            }
        };
    }

    private final class StreamSession {
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean expired = new AtomicBoolean();
        private final Object sendLock = new Object();
        private final Object timerLock = new Object();
        private volatile Thread worker;
        private ScheduledFuture<?> heartbeat;
        private ScheduledFuture<?> deadline;

        private StreamSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void start() {
            worker = Thread.currentThread();
            synchronized (timerLock) {
                if (closed.get()) {
                    return;
                }
                // 截止时间先于首次网络写入启动；不与心跳共用调度线程。
                deadline = DEADLINE_EXECUTOR.schedule(this::expire, runTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            // comment 不会进入前端聊天正文，同时立即刷新响应头。
            if (send(SseEmitter.event().comment("connected"))) {
                synchronized (timerLock) {
                    if (!closed.get() && !cancelled) {
                        heartbeat = HEARTBEAT_EXECUTOR.scheduleAtFixedRate(() -> {
                            if (!cancelled) {
                                send(SseEmitter.event().comment("heartbeat"));
                            }
                        }, 15, 15, TimeUnit.SECONDS);
                    }
                }
            }
        }

        private void expire() {
            if (closed.get() || !expired.compareAndSet(false, true)) {
                return;
            }
            cancelled = true;
            state = AgentState.ERROR;
            Thread activeWorker = worker;
            if (activeWorker != null && activeWorker != Thread.currentThread()) {
                activeWorker.interrupt();
            }
            stopHeartbeat();
            // 调度线程只发布取消，不执行可能阻塞的网络 I/O。
            AGENT_EXECUTOR.execute(() -> finish("本次任务已超过时间上限，请缩小任务范围后重试。", true));
        }

        private boolean send(SseEmitter.SseEventBuilder event) {
            synchronized (sendLock) {
                if (closed.get()) {
                    return false;
                }
                try {
                    emitter.send(event);
                    return true;
                } catch (IOException | IllegalStateException e) {
                    // 写失败后不能再发送错误消息；Servlet 容器负责处理连接错误。
                    disconnect();
                    log.debug("SSE connection closed while sending", e);
                    return false;
                }
            }
        }

        private void delta(String text) {
            try {
                // JSON 封装保留 token 的空格、换行及看似 JSON 的正文，避免前端二次解析失真。
                String data = EVENT_JSON.writeValueAsString(Map.of("delta", text));
                if (!send(SseEmitter.event().name("delta").data(data, UTF8_TEXT))) {
                    throw new CancellationException("SSE connection closed");
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Cannot encode answer delta", e);
            }
        }

        private void finish(String message, boolean error) {
            synchronized (sendLock) {
                if (closed.get() || (!error && cancelled)) {
                    return;
                }
                if ((error || !answerStreamed) && !send(error ? SseEmitter.event().name("error").data(message, UTF8_TEXT)
                        : SseEmitter.event().data(message, UTF8_TEXT))) {
                    return;
                }
                if (!error && cancelled) {
                    return;
                }
                if (!send(SseEmitter.event().name("done").data("[DONE]"))) {
                    return;
                }
                if (closed.compareAndSet(false, true)) {
                    stopHeartbeat();
                    emitter.complete();
                }
            }
        }

        private void disconnect() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            // 先发布取消并中断工作线程，不等待可能被网络写操作占用的发送锁。
            cancelled = true;
            state = AgentState.ERROR;
            Thread activeWorker = worker;
            if (activeWorker != null && activeWorker != Thread.currentThread()) {
                activeWorker.interrupt();
            }
            stopHeartbeat();
        }

        private void stopHeartbeat() {
            synchronized (timerLock) {
                if (heartbeat != null) {
                    heartbeat.cancel(false);
                    heartbeat = null;
                }
                if (deadline != null) {
                    deadline.cancel(false);
                    deadline = null;
                }
            }
        }
    }

    /**
     * 执行单个步骤
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}
