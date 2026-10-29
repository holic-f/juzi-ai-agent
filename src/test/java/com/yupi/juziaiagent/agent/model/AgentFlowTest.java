package com.yupi.juziaiagent.agent.model;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentFlowTest {

    @RestController
    static class StreamController {
        @GetMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
        public SseEmitter stream() {
            return new BaseAgent() {
                @Override
                public String step() {
                    getMessageList().add(new AssistantMessage("真实 SSE 答复"));
                    setState(AgentState.FINISHED);
                    return "";
                }
            }.runStream("任务");
        }
    }

    @Test
    void realEmitterFlushesCommentsAnswerAndDoneThroughSpringMvc() throws Exception {
        MockMvc mvc = standaloneSetup(new StreamController()).build();
        MvcResult result = mvc.perform(get("/stream"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(5000);
        mvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(":connected\n\ndata:真实 SSE 答复\n\nevent:done\ndata:[DONE]\n\n"));
    }

    @Test
    void noToolCallFinishesAgent() {
        FakeAgent agent = new FakeAgent();
        agent.setState(AgentState.RUNNING);
        assertEquals("<html>工具原始结果</html>", agent.step());
        assertEquals(AgentState.RUNNING, agent.getState());
        assertEquals("思考完成", agent.step());
        assertEquals(AgentState.FINISHED, agent.getState());
    }

    @Test
    void streamOnlySendsFinalAnswerAndCompletionMarker() throws InterruptedException {
        FakeAgent agent = new FakeAgent();
        agent.setMaxSteps(3);
        agent.runStream("请整理结果");
        await(agent.cleaned);
        assertEquals(List.of(":connected\n\n", "data:最终答复\n\n", "event:done\ndata:[DONE]\n\n"), agent.emitter.frames);
        assertEquals(1, agent.emitter.completions.get());
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals(1, agent.cleanups.get());
    }

    @Test
    void timeoutStopsFurtherStepsEvenWhenInFlightStepIgnoresInterrupt() throws InterruptedException {
        // 容器超时也通过同一超时收尾，发送明确错误。
        BlockingAgent agent = new BlockingAgent();
        agent.runStream("慢任务");
        await(agent.entered);
        agent.emitter.disconnect("timeout");
        agent.release.countDown();
        await(agent.cleaned);
        assertTrue(agent.interrupted);
        assertEquals(1, agent.calls.get());
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void configuredDeadlineSendsOneErrorAndHoldsPermitUntilWorkerExits() throws InterruptedException {
        BlockingAgent agent = new BlockingAgent();
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        agent.runStream("慢任务", 150, () -> {
            releases.incrementAndGet();
            exited.countDown();
        });
        try {
            await(agent.entered);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (agent.emitter.completions.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, agent.emitter.completions.get());
            assertEquals(0, releases.get(), "收到超时不代表工作线程已经退出");
            assertEquals(0, agent.cleanups.get());
            assertEquals(1, agent.emitter.frames.stream().filter(frame -> frame.contains("event:error")).count());
            assertEquals(1, agent.emitter.frames.stream().filter(frame -> frame.contains("event:done")).count());
            assertTrue(agent.emitter.frames.stream().anyMatch(frame -> frame.contains("超过时间上限")));
        } finally {
            agent.release.countDown();
        }
        await(exited);
        assertTrue(agent.interrupted);
        assertEquals(1, agent.calls.get());
        assertEquals(1, releases.get());
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void deadlineInterruptsWorkerEvenWhileItsFirstNetworkWriteIsBlocked() throws InterruptedException {
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger steps = new AtomicInteger();
        TestAgent agent = new TestAgent() {
            @Override
            protected SseEmitter createEmitter() {
                return new SseEmitter() {
                    @Override
                    public void send(SseEventBuilder event) {
                        sending.countDown();
                        boolean wasInterrupted = false;
                        boolean done = false;
                        while (!done) {
                            try { releaseSend.await(); done = true; }
                            catch (InterruptedException ignored) {
                                wasInterrupted = true;
                                interrupted.countDown();
                            }
                        }
                        if (wasInterrupted) { Thread.currentThread().interrupt(); }
                    }
                };
            }

            @Override
            public String step() { steps.incrementAndGet(); return ""; }
        };
        agent.runStream("阻塞写入", 150, exited::countDown);
        try {
            await(sending);
            await(interrupted);
            assertEquals(1, exited.getCount());
            assertEquals(0, steps.get());
        } finally {
            releaseSend.countDown();
        }
        await(exited);
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void clientCompletionStopsFurtherSteps() throws InterruptedException {
        assertDisconnectStopsWork("completion");
    }

    @Test
    void transportErrorStopsFurtherSteps() throws InterruptedException {
        assertDisconnectStopsWork("error");
    }

    private void assertDisconnectStopsWork(String reason) throws InterruptedException {
        BlockingAgent agent = new BlockingAgent();
        agent.runStream("慢任务");
        await(agent.entered);
        agent.emitter.disconnect(reason);
        // cleanup 不能与尚未退出的工具/模型操作并发执行。
        assertEquals(0, agent.cleanups.get());
        agent.release.countDown();
        await(agent.cleaned);
        assertTrue(agent.interrupted);
        assertEquals(1, agent.calls.get());
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(1, agent.cleanups.get());
        assertEquals(List.of(":connected\n\n"), agent.emitter.frames);
        assertEquals(0, agent.emitter.sendsAfterClose.get());
        assertEquals(0, agent.emitter.completions.get());
    }

    @Test
    void slowTaskReceivesHeartbeatAndHeartbeatWriteFailureCancelsWorker() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        TestAgent agent = new TestAgent() {
            @Override
            public String step() {
                entered.countDown();
                try {
                    new CountDownLatch(1).await(25, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "";
            }
        };
        // 第一帧是 connected；15 秒后的第二次发送是心跳，模拟客户端此时断开。
        agent.emitter.failAt = 2;
        agent.emitter.ioFailure = true;
        agent.runStream("慢任务");
        await(entered);
        assertTrue(agent.cleaned.await(20, TimeUnit.SECONDS), "心跳写失败应取消正在等待的任务");
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(2, agent.emitter.attempts.get());
        assertEquals(1, agent.cleanups.get());
        assertEquals(List.of(":connected\n\n"), agent.emitter.frames);
    }

    @Test
    void alreadyCompletedEmitterDoesNotTriggerAnotherErrorSend() throws InterruptedException {
        FakeAgent agent = new FakeAgent();
        agent.emitter.failAt = 2;
        agent.runStream("请整理结果");
        await(agent.cleaned);
        assertEquals(2, agent.emitter.attempts.get());
        assertEquals(0, agent.emitter.completions.get());
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void initialWriteFailureNeverStartsModelWork() throws InterruptedException {
        FakeAgent agent = new FakeAgent();
        agent.emitter.failAt = 1;
        agent.emitter.ioFailure = true;
        agent.runStream("请整理结果");
        await(agent.cleaned);
        assertEquals(0, agent.calls);
        assertEquals(1, agent.emitter.attempts.get());
        assertEquals(1, agent.cleanups.get());
    }

    @Test
    void reachingStepLimitIsAnExplicitErrorEvent() throws InterruptedException {
        TestAgent agent = new TestAgent() {
            @Override
            public String step() {
                return "继续";
            }
        };
        agent.setMaxSteps(2);
        agent.runStream("任务");
        await(agent.cleaned);
        assertEquals(2, agent.getCurrentStep());
        assertTrue(agent.emitter.frames.get(1).startsWith("event:error\ndata:未能在最大步骤数内"));
        assertEquals("event:done\ndata:[DONE]\n\n", agent.emitter.frames.get(2));
        assertEquals(AgentState.ERROR, agent.getState());
    }

    @Test
    void executionFailureSendsOneErrorAndCleansOnce() throws InterruptedException {
        TestAgent agent = new TestAgent() {
            @Override
            public String step() {
                throw new IllegalStateException("model unavailable");
            }
        };
        agent.runStream("任务");
        await(agent.cleaned);
        assertTrue(agent.emitter.frames.get(1).startsWith("event:error\ndata:智能体执行失败"));
        assertEquals(3, agent.emitter.attempts.get());
        assertEquals(1, agent.emitter.completions.get());
        assertEquals(1, agent.cleanups.get());
    }

    @Test
    void disconnectDuringThinkDoesNotInvokeTool() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger toolCalls = new AtomicInteger();
        FakeAgent agent = new FakeAgent() {
            @Override
            public boolean think() {
                entered.countDown();
                waitIgnoringInterrupt(release);
                return true;
            }

            @Override
            public String act() {
                toolCalls.incrementAndGet();
                return "";
            }
        };
        agent.runStream("任务");
        await(entered);
        agent.emitter.disconnect("timeout");
        release.countDown();
        await(agent.cleaned);
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(1, agent.emitter.attempts.get());
        assertEquals(0, toolCalls.get(), "连接关闭后不应调用工具");
    }

    @Test
    void disconnectDuringToolBatchSkipsRemainingToolsEvenIfFirstClearsInterrupt() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleaned = new CountDownLatch(1);
        AtomicInteger secondCalls = new AtomicInteger();
        RecordingEmitter emitter = new RecordingEmitter();
        ToolCallback first = tool("first", () -> {
            entered.countDown();
            waitIgnoringInterrupt(release);
            Thread.interrupted(); // 模拟 SDK 吞掉中断，后续检查仍必须看到连接已结束。
        });
        ToolCallback second = tool("second", secondCalls::incrementAndGet);
        ToolCallAgent agent = new ToolCallAgent(new ToolCallback[]{first, second}) {
            @Override
            public boolean think() {
                setToolCallChatResponse(new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(List.of(
                                new AssistantMessage.ToolCall("1", "function", "first", "{}"),
                                new AssistantMessage.ToolCall("2", "function", "second", "{}")
                        )).build()))));
                return true;
            }

            @Override
            protected SseEmitter createEmitter() { return emitter; }

            @Override
            protected void cleanup() { cleaned.countDown(); }
        };
        agent.runStream("执行两个工具");
        await(entered);
        emitter.disconnect("error");
        release.countDown();
        await(cleaned);
        assertEquals(0, secondCalls.get());
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(List.of(":connected\n\n"), emitter.frames);
    }

    private static ToolCallback tool(String name, Runnable action) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String input) {
                action.run();
                return "ok";
            }
        };
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "异步任务未在时限内完成");
    }

    private static void waitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private abstract static class TestAgent extends ReActAgent {
        final RecordingEmitter emitter = new RecordingEmitter();
        final CountDownLatch cleaned = new CountDownLatch(1);
        final AtomicInteger cleanups = new AtomicInteger();

        @Override
        public boolean think() { return true; }

        @Override
        public String act() { return ""; }

        @Override
        protected SseEmitter createEmitter() { return emitter; }

        @Override
        protected void cleanup() {
            cleanups.incrementAndGet();
            cleaned.countDown();
        }
    }

    private static class FakeAgent extends TestAgent {
        private int calls;

        @Override
        public boolean think() {
            if (calls++ == 0) return true;
            getMessageList().add(new AssistantMessage("最终答复"));
            return false;
        }

        @Override
        public String act() { return "<html>工具原始结果</html>"; }
    }

    private static final class BlockingAgent extends TestAgent {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean interrupted;

        @Override
        public String step() {
            calls.incrementAndGet();
            entered.countDown();
            waitIgnoringInterrupt(release);
            interrupted = Thread.currentThread().isInterrupted();
            // 模拟无法立即取消的 SDK 返回后覆盖业务状态。
            setState(AgentState.FINISHED);
            return "迟到的结果";
        }
    }

    private static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger sendsAfterClose = new AtomicInteger();
        final AtomicInteger completions = new AtomicInteger();
        private Runnable timeout;
        private Runnable completion;
        private Consumer<Throwable> error;
        private volatile boolean closed;
        int failAt = -1;
        boolean ioFailure;

        @Override
        public void onTimeout(Runnable callback) { timeout = callback; }

        @Override
        public void onCompletion(Runnable callback) { completion = callback; }

        @Override
        public void onError(Consumer<Throwable> callback) { error = callback; }

        @Override
        public synchronized void send(SseEventBuilder event) throws IOException {
            int attempt = attempts.incrementAndGet();
            if (closed) sendsAfterClose.incrementAndGet();
            if (closed || attempt == failAt) {
                if (ioFailure) throw new IOException("connection reset");
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
            StringBuilder frame = new StringBuilder();
            event.build().forEach(part -> frame.append(part.getData()));
            frames.add(frame.toString());
        }

        @Override
        public void complete() {
            completions.incrementAndGet();
            closed = true;
            completion.run();
        }

        void disconnect(String reason) {
            closed = true;
            switch (reason) {
                case "timeout" -> timeout.run();
                case "completion" -> completion.run();
                case "error" -> error.accept(new IOException("connection reset"));
                default -> throw new IllegalArgumentException(reason);
            }
            completion.run();
        }
    }
}
