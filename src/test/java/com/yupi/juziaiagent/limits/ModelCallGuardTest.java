package com.yupi.juziaiagent.limits;

import com.yupi.juziaiagent.config.ModelLimitsProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 纯本地计数、线程和假流，无真实模型、工具或外部服务。 */
class ModelCallGuardTest {
    private final ModelLimitsProperties limits = new ModelLimitsProperties();
    private final List<ModelCallGuard> guards = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        guards.forEach(ModelCallGuard::destroy);
    }

    private ModelCallGuard guard() {
        ModelCallGuard guard = new ModelCallGuard(limits);
        guards.add(guard);
        return guard;
    }

    @Test
    void rejectsEmptyAndOverlongInputsBeforeAnyWorkAndCountsUnicodeCharacters() {
        limits.setMaxInputChars(2);
        ModelCallGuard guard = guard();
        AtomicBoolean called = new AtomicBoolean();
        for (String input : new String[]{null, "", " \n", "abc"}) {
            var error = assertThrows(ModelLimitException.class, () -> guard.call(input, () -> called.getAndSet(true)));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        }
        assertFalse(called.get());
        assertEquals("ok", guard.call("😀中", () -> "ok"));
    }

    @Test
    void rollingMinuteCountsOnlyAcceptedTasksAndExpiresAtBoundary() {
        limits.setMaxTasksPerMinute(2);
        AtomicLong now = new AtomicLong();
        ModelCallGuard guard = new ModelCallGuard(limits, now::get);
        guards.add(guard);
        guard.acquire("a").close();
        now.set(TimeUnit.SECONDS.toNanos(30));
        guard.acquire("b").close();
        ModelLimitException error = assertThrows(ModelLimitException.class, () -> guard.acquire("c"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.getStatus());
        assertEquals(30, error.getRetryAfterSeconds());
        now.set(TimeUnit.MINUTES.toNanos(1));
        guard.acquire("d").close();
        assertThrows(ModelLimitException.class, () -> guard.acquire("e"));
    }

    @Test
    void concurrentAdmissionIsAtomicAndReleaseIsIdempotent() throws Exception {
        ModelCallGuard guard = guard();
        ExecutorService workers = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch admitted = new CountDownLatch(12);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                tasks.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    ModelCallGuard.Lease lease;
                    try {
                        lease = guard.acquire("task");
                    } catch (ModelLimitException error) {
                        rejections.incrementAndGet();
                        admitted.countDown();
                        return null;
                    }
                    successes.incrementAndGet();
                    admitted.countDown();
                    try {
                        assertTrue(release.await(2, TimeUnit.SECONDS));
                    } finally {
                        lease.close();
                        lease.close();
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(admitted.await(2, TimeUnit.SECONDS));
            assertEquals(2, successes.get());
            assertEquals(10, rejections.get());
            release.countDown();
            for (Future<?> task : tasks) { task.get(2, TimeUnit.SECONDS); }
            try (var first = guard.acquire("next"); var second = guard.acquire("next")) {
                assertThrows(ModelLimitException.class, () -> guard.acquire("third"));
            }
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void syncErrorPreservesOriginalExceptionAndReleasesSlot() {
        limits.setMaxConcurrentTasks(1);
        ModelCallGuard guard = guard();
        IllegalStateException failure = new IllegalStateException("fake failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> guard.call("a", () -> { throw failure; })));
        assertEquals("next", guard.call("b", () -> "next"));
    }

    @Test
    void timeoutKeepsSlotUntilNonCooperativeWorkReallyExits() throws Exception {
        limits.setMaxConcurrentTasks(1);
        limits.setChatTimeout(Duration.ofMillis(150));
        ModelCallGuard guard = guard();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> {
                var error = assertThrows(ModelLimitException.class, () -> guard.call("slow", () -> {
                    entered.countDown();
                    boolean done = false;
                    while (!done) {
                        try { release.await(); done = true; }
                        catch (InterruptedException ignored) { interrupted.set(true); }
                    }
                    return "late";
                }));
                assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            result.get(2, TimeUnit.SECONDS);
            assertThrows(ModelLimitException.class, () -> guard.acquire("next"));
            release.countDown();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean available = false;
            while (System.nanoTime() < until) {
                try (var lease = guard.acquire("next")) { available = true; break; }
                catch (ModelLimitException ignored) { Thread.onSpinWait(); }
            }
            assertTrue(available);
            assertTrue(interrupted.get());
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void streamCompletionErrorAndCancellationAllReleaseSlots() {
        limits.setMaxConcurrentTasks(1);
        ModelCallGuard guard = guard();
        assertEquals(List.of("a", "b"), guard.stream("one", () -> Flux.just("a", "b")).collectList().block());
        IllegalStateException failure = new IllegalStateException("fake failure");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> guard.stream("two", () -> Flux.error(failure)).blockLast()));
        var subscription = guard.stream("three", Flux::never).subscribe();
        assertThrows(ModelLimitException.class, () -> guard.acquire("busy"));
        subscription.dispose();
        assertEquals("ok", guard.call("four", () -> "ok"));
    }

    @Test
    void streamDeadlineIsTotalTimeEvenWhenChunksKeepArriving() {
        limits.setChatTimeout(Duration.ofMillis(100));
        limits.setMaxConcurrentTasks(1);
        ModelCallGuard guard = guard();
        AtomicBoolean cancelled = new AtomicBoolean();
        var error = assertThrows(ModelLimitException.class, () -> guard.stream("stream", () ->
                        Flux.interval(Duration.ofMillis(10)).doOnCancel(() -> cancelled.set(true)))
                .blockLast(Duration.ofSeconds(2)));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatus());
        assertTrue(cancelled.get());
        guard.acquire("next").close();
    }

    @Test
    void streamAdmissionIsPerSubscriptionAndDoesNotEagerlyCreateUpstream() {
        limits.setMaxTasksPerMinute(1);
        ModelCallGuard guard = guard();
        AtomicInteger calls = new AtomicInteger();
        Flux<String> stream = guard.stream("one", () -> { calls.incrementAndGet(); return Flux.just("ok"); });
        assertEquals(0, calls.get());
        assertEquals("ok", stream.blockLast());
        assertThrows(ModelLimitException.class, stream::blockLast);
        assertEquals(1, calls.get());
    }
}
