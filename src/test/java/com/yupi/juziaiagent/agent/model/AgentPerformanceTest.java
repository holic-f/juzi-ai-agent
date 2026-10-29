package com.yupi.juziaiagent.agent.model;

import com.yupi.juziaiagent.tools.TerminateTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentPerformanceTest {
    @Test
    void terminateWithFinalAnswerDoesNotMakeAnotherModelRequest() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("doTerminate", "{\"finalAnswer\":\"已经完成\"}"));
        RunningAgent agent = new RunningAgent(ToolCallbacks.from(new TerminateTool()), model);
        agent.runStream("任务");
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(agent.emitter.frames.stream().anyMatch(frame -> frame.contains("已经完成")));
        verify(model, times(1)).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void legacyTerminateStreamsFinalSummaryBeforeModelCompletesWithoutDuplicatingAnswer() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("doTerminate", "{}"));
        CountDownLatch firstDelivered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(
                Flux.just(answer("第一段")),
                Flux.defer(() -> {
                    firstDelivered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) return Flux.error(new IllegalStateException("test release timeout"));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Flux.error(e);
                    }
                    return Flux.just(answer(" 第二段"));
                })));
        RunningAgent agent = new RunningAgent(ToolCallbacks.from(new TerminateTool()), model);
        agent.runStream("任务");
        try {
            await(firstDelivered);
            await(agent.emitter.deltaSent);
            assertTrue(agent.emitter.frames.stream().anyMatch(frame -> frame.startsWith("event:delta\n") && frame.contains("第一段")),
                    "首段内容应在模型完成前到达 SSE");
            assertEquals(0, agent.emitter.completions.get());
        } finally {
            release.countDown();
        }
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertEquals(2, agent.emitter.frames.stream().filter(frame -> frame.startsWith("event:delta\n")).count());
        assertTrue(agent.emitter.frames.stream().noneMatch(frame -> frame.startsWith("data:")), "不能在 delta 后再发一遍完整答案");
        assertTrue(agent.emitter.frames.get(agent.emitter.frames.size() - 1).contains("event:done"));
        verify(model, times(1)).call(any(Prompt.class));
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void disconnectCancelsSummarySubscriptionAndDoesNotSendDone() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("doTerminate", "{}"));
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch disposed = new CountDownLatch(1);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(answer("部分内容")),
                Flux.<ChatResponse>never().doOnSubscribe(subscription -> subscribed.countDown()))
                .doOnCancel(disposed::countDown));
        RunningAgent agent = new RunningAgent(ToolCallbacks.from(new TerminateTool()), model);
        agent.runStream("任务");
        await(subscribed);
        agent.emitter.disconnect();
        await(agent.cleaned);
        await(disposed);
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(0, agent.emitter.completions.get());
        assertTrue(agent.emitter.frames.stream().noneMatch(frame -> frame.contains("event:done")));
    }

    @Test
    void streamFailurePreservesPartialAnswerAndSendsOneErrorWithoutRetry() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("doTerminate", "{}"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(answer("部分内容")),
                Flux.error(new IllegalStateException("provider stream failed"))));
        RunningAgent agent = new RunningAgent(ToolCallbacks.from(new TerminateTool()), model);
        agent.runStream("任务");
        await(agent.cleaned);
        assertEquals(AgentState.ERROR, agent.getState());
        assertEquals(1, agent.emitter.frames.stream().filter(frame -> frame.contains("event:error")).count());
        assertEquals(1, agent.emitter.frames.stream().filter(frame -> frame.contains("event:delta")).count());
        verify(model, times(1)).call(any(Prompt.class));
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test
    void directAnswerKeepsSingleModelCallAndDoesNotLeakPlanningText() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(answer("直接答复"));
        RunningAgent agent = new RunningAgent(new ToolCallback[0], model);
        agent.runStream("你好");
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(agent.emitter.frames.contains("data:直接答复\n\n"));
        verify(model, times(1)).call(any(Prompt.class));
        verify(model, never()).stream(any(Prompt.class));
    }

    @Test
    void oversizedToolResultIsBoundedBeforeTheNextModelRequest() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("readFile", "{}"), answer("总结"));
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("readFile").description("read").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override
            public String call(String input) { return "HEAD\n" + "x".repeat(20000) + "\nTAIL"; }
        };
        RunningAgent agent = new RunningAgent(new ToolCallback[]{tool}, model);
        agent.runStream("总结文件");
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        String result = agent.getMessageList().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .findFirst().orElseThrow().responseData();
        assertTrue(result.length() <= 6000, "每条工具结果必须受字符预算限制");
        assertTrue(result.startsWith("HEAD"));
        assertTrue(result.endsWith("TAIL"));
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void frameworkConvertedToolExceptionIsAlsoBoundedBeforeNextRequest() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("brokenTool", "{}"), answer("失败说明"));
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("brokenTool").description("broken").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override
            public String call(String input) {
                throw new org.springframework.ai.tool.execution.ToolExecutionException(getToolDefinition(),
                        new IllegalStateException("ERROR_HEAD" + "x".repeat(20000) + "ERROR_TAIL"));
            }
        };
        RunningAgent agent = new RunningAgent(new ToolCallback[]{tool}, model);
        agent.runStream("任务");
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        String result = agent.getMessageList().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .findFirst().orElseThrow().responseData();
        assertTrue(result.length() <= 6000, "框架生成的错误文本也必须限长");
        assertTrue(result.contains("ERROR_HEAD"));
        assertTrue(result.endsWith("ERROR_TAIL"));
    }

    @Test
    void repeatedPlanningPromptDoesNotGrowPersistedConversation() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(toolCall("readFile", "{}"), answer("完成"));
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("readFile").description("read").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override
            public String call(String input) { return "file result"; }
        };
        RunningAgent agent = new RunningAgent(new ToolCallback[]{tool}, model);
        agent.setNextStepPrompt("NEXT_STEP_INSTRUCTION");
        agent.runStream("用户任务");
        await(agent.cleaned);
        assertTrue(agent.getMessageList().stream().noneMatch(message -> "NEXT_STEP_INSTRUCTION".equals(message.getText())));
        ArgumentCaptor<Prompt> requests = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(requests.capture());
        for (Prompt request : requests.getAllValues()) {
            assertEquals(1, request.getInstructions().stream().filter(message -> "NEXT_STEP_INSTRUCTION".equals(message.getText())).count());
        }
    }

    @Test
    void mixedTerminationCannotUsePrewrittenAnswerBeforeOtherToolResult() throws Exception {
        ChatModel model = mock(ChatModel.class);
        AssistantMessage calls = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("1", "function", "doTerminate", "{\"finalAnswer\":\"PREMATURE_SUCCESS\"}"),
                new AssistantMessage.ToolCall("2", "function", "generatePDF", "{}")
        )).build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(calls))));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(answer("文件生成失败，请调整后重试。")));
        ToolCallback failing = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("generatePDF").description("pdf").inputSchema("{\"type\":\"object\"}").build();
            }
            @Override
            public String call(String input) { return "Error generating PDF: missing font"; }
        };
        ToolCallback termination = ToolCallbacks.from(new TerminateTool())[0];
        RunningAgent agent = new RunningAgent(new ToolCallback[]{termination, failing}, model);
        agent.runStream("生成文件");
        await(agent.cleaned);
        assertEquals(AgentState.FINISHED, agent.getState());
        assertTrue(agent.emitter.frames.stream().noneMatch(frame -> frame.contains("PREMATURE_SUCCESS")));
        assertTrue(agent.emitter.frames.stream().anyMatch(frame -> frame.contains("文件生成失败")));
        ArgumentCaptor<Prompt> summary = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(1)).stream(summary.capture());
        assertTrue(summary.getValue().getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                .anyMatch(response -> response.responseData().contains("missing font")));
    }

    @Test
    void timingLogsExposeDurationsAndSizesWithoutLoggingUserOrToolContent() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.yupi.juziaiagent.agent.model");
        CountDownLatch taskLogged = new CountDownLatch(1);
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent event) {
                super.append(event);
                if (event.getFormattedMessage().startsWith("agent_task")) taskLogged.countDown();
            }
        };
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(toolCall("doTerminate", "{\"finalAnswer\":\"PRIVATE_ANSWER\"}"));
            RunningAgent agent = new RunningAgent(ToolCallbacks.from(new TerminateTool()), model);
            agent.runStream("PRIVATE_USER_INPUT");
            await(agent.cleaned);
            await(taskLogged);
            List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(logs.stream().anyMatch(line -> line.startsWith("agent_model ") && line.contains("elapsed_ms=") && line.contains("input_chars_estimate=")));
            assertTrue(logs.stream().anyMatch(line -> line.startsWith("agent_tool ") && line.contains("raw_chars=") && line.contains("context_chars=")));
            assertTrue(logs.stream().anyMatch(line -> line.startsWith("agent_task ") && line.contains("state=FINISHED") && line.contains("elapsed_ms=")));
            assertTrue(logs.stream().noneMatch(line -> line.contains("PRIVATE_USER_INPUT") || line.contains("PRIVATE_ANSWER")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static ChatResponse toolCall(String name, String args) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("内部规划，不能作为最终答复")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, args))).build())));
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(7, TimeUnit.SECONDS), "异步任务未完成");
    }

    private static class RunningAgent extends ToolCallAgent {
        final RecordingEmitter emitter = new RecordingEmitter();
        final CountDownLatch cleaned = new CountDownLatch(1);
        RunningAgent(ToolCallback[] tools, ChatModel model) {
            super(tools);
            setName("test-agent");
            setSystemPrompt("test");
            setChatClient(ChatClient.builder(model).build());
        }
        @Override
        protected SseEmitter createEmitter() { return emitter; }
        @Override
        protected void cleanup() { cleaned.countDown(); }
    }

    private static class RecordingEmitter extends SseEmitter {
        final List<String> frames = new CopyOnWriteArrayList<>();
        final AtomicInteger completions = new AtomicInteger();
        final CountDownLatch deltaSent = new CountDownLatch(1);
        Runnable complete = () -> {};
        Consumer<Throwable> error = ignored -> {};
        @Override
        public void onCompletion(Runnable callback) { complete = callback; }
        @Override
        public void onError(Consumer<Throwable> callback) { error = callback; }
        @Override
        public void send(SseEventBuilder event) throws IOException {
            StringBuilder wire = new StringBuilder();
            event.build().forEach(part -> wire.append(part.getData()));
            frames.add(wire.toString());
            if (wire.toString().startsWith("event:delta\n")) deltaSent.countDown();
        }
        @Override
        public void complete() { completions.incrementAndGet(); complete.run(); }
        void disconnect() { error.accept(new IOException("client disconnected")); complete.run(); }
    }
}
