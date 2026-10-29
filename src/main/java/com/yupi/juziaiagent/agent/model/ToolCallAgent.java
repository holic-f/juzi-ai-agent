package com.yupi.juziaiagent.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** ReAct 工具代理：规划调用不泄露到正文，必要的最后一次总结才增量发送。 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ToolCallback[] availableTools;
    private ChatResponse toolCallChatResponse;
    private final ToolCallingManager toolCallingManager;
    private final OpenAiChatOptions chatOptions;
    private int maxToolResultChars = 6000;

    public ToolCallAgent(ToolCallback[] availableTools) {
        this.availableTools = Arrays.stream(availableTools).map(this::cancellableTool).toArray(ToolCallback[]::new);
        this.toolCallingManager = ToolCallingManager.builder().build();
        this.chatOptions = OpenAiChatOptions.builder().internalToolExecutionEnabled(false).build();
    }

    public void setMaxToolResultChars(int maxToolResultChars) {
        if (maxToolResultChars < 256) {
            throw new IllegalArgumentException("maxToolResultChars must be at least 256");
        }
        this.maxToolResultChars = maxToolResultChars;
    }

    private ToolCallback cancellableTool(ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
            @Override
            public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
            @Override
            public String call(String input) {
                checkCancelled();
                return executeMeasuredTool(delegate, () -> delegate.call(input));
            }
            @Override
            public String call(String input, ToolContext context) {
                checkCancelled();
                return executeMeasuredTool(delegate, () -> delegate.call(input, context));
            }
        };
    }

    private String executeMeasuredTool(ToolCallback tool, Supplier<String> action) {
        long started = System.nanoTime();
        boolean completed = false;
        int rawChars = 0;
        int contextChars = 0;
        try {
            String raw = action.get();
            checkCancelled();
            rawChars = raw == null ? 0 : raw.length();
            String result = ToolResultCompactor.compact(tool.getToolDefinition().name(), raw, maxToolResultChars);
            contextChars = result.length();
            completed = true;
            return result;
        } finally {
            // completed 表示回调正常返回，不代表返回的业务内容一定成功。
            log.info("agent_tool run={} step={} tool={} completed={} elapsed_ms={} raw_chars={} context_chars={}",
                    getRunId(), getCurrentStep(), tool.getToolDefinition().name(), completed,
                    elapsedMillis(started), rawChars, contextChars);
        }
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    // 请求文本量的近似值，非 token 数或完整 JSON 字节数；实际 token 以 provider usage 为准。
    private long inputChars(List<Message> instructions, boolean withTools) {
        long chars = getSystemPrompt() == null ? 0 : getSystemPrompt().length();
        for (Message message : instructions) {
            if (message instanceof ToolResponseMessage tool) {
                chars += tool.getResponses().stream().mapToLong(response -> response.responseData().length()).sum();
            } else {
                chars += message.getText() == null ? 0 : message.getText().length();
                if (message instanceof AssistantMessage assistant) {
                    chars += assistant.getToolCalls().stream().mapToLong(call -> call.arguments() == null ? 0 : call.arguments().length()).sum();
                }
            }
        }
        if (withTools) {
            for (ToolCallback tool : availableTools) {
                ToolDefinition definition = tool.getToolDefinition();
                chars += definition.name().length() + definition.description().length() + definition.inputSchema().length();
            }
        }
        return chars;
    }

    @Override
    public boolean think() {
        checkCancelled();
        // 每轮提示只放在本次请求中，不让相同提示随轮数在持久上下文里反复累积。
        List<Message> instructions = new ArrayList<>(getMessageList());
        if (getNextStepPrompt() != null && !getNextStepPrompt().isBlank()) {
            instructions.add(new UserMessage(getNextStepPrompt()));
        }
        long inputChars = inputChars(instructions, true);
        long started = System.nanoTime();
        boolean completed = false;
        try {
            toolCallChatResponse = getChatClient().prompt(new Prompt(instructions, chatOptions))
                    .system(getSystemPrompt()).toolCallbacks(availableTools).call().chatResponse();
            checkCancelled();
            if (toolCallChatResponse == null || toolCallChatResponse.getResult() == null) {
                throw new IllegalStateException("模型未返回答复");
            }
            AssistantMessage answer = toolCallChatResponse.getResult().getOutput();
            boolean hasTools = !answer.getToolCalls().isEmpty();
            if (!hasTools) {
                getMessageList().add(answer);
            }
            completed = true;
            log.info("agent_model_result run={} step={} model={} prompt_tokens={} completion_tokens={} tool_calls={}",
                    getRunId(), getCurrentStep(), toolCallChatResponse.getMetadata().getModel(),
                    toolCallChatResponse.getMetadata().getUsage().getPromptTokens(),
                    toolCallChatResponse.getMetadata().getUsage().getCompletionTokens(), answer.getToolCalls().size());
            return hasTools;
        } catch (Exception e) {
            checkCancelled();
            throw new IllegalStateException("模型调用失败", e);
        } finally {
            log.info("agent_model run={} step={} phase=plan completed={} elapsed_ms={} input_chars_estimate={}",
                    getRunId(), getCurrentStep(), completed, elapsedMillis(started), inputChars);
        }
    }

    @Override
    public String act() {
        checkCancelled();
        if (toolCallChatResponse == null || !toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        OpenAiChatOptions executionOptions = chatOptions.copy();
        executionOptions.setToolCallbacks(Arrays.asList(availableTools));
        ToolExecutionResult result = toolCallingManager.executeToolCalls(
                new Prompt(getMessageList(), executionOptions), toolCallChatResponse);
        checkCancelled();
        List<Message> boundedHistory = new ArrayList<>();
        for (Message message : result.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolMessage) {
                List<ToolResponseMessage.ToolResponse> bounded = toolMessage.getResponses().stream()
                        .map(response -> new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                                ToolResultCompactor.compact(response.name(), response.responseData(), maxToolResultChars)))
                        .toList();
                boundedHistory.add(ToolResponseMessage.builder().responses(bounded).metadata(toolMessage.getMetadata()).build());
            } else {
                boundedHistory.add(message);
            }
        }
        setMessageList(boundedHistory);
        ToolResponseMessage responses = (ToolResponseMessage) getMessageList().get(getMessageList().size() - 1);
        boolean terminate = responses.getResponses().stream().anyMatch(response -> response.name().equals("doTerminate"));
        String names = responses.getResponses().stream().map(ToolResponseMessage.ToolResponse::name)
                .collect(Collectors.joining(", "));
        if (terminate) {
            String readyAnswer = readyTerminationAnswer();
            if (readyAnswer != null) {
                getMessageList().add(new AssistantMessage(readyAnswer));
                log.info("agent_final run={} source=terminate skipped_summary=true", getRunId());
            } else {
                streamFinalSummary();
            }
            checkCancelled();
            setState(AgentState.FINISHED);
        }
        return "已执行工具: " + names;
    }

    private String readyTerminationAnswer() {
        // 同批还有其它工具时，预写答复尚未见到其结果，必须重新汇总，不能假称成功。
        List<AssistantMessage.ToolCall> calls = toolCallChatResponse.getResult().getOutput().getToolCalls();
        if (calls.size() != 1 || !"doTerminate".equals(calls.get(0).name())) {
            return null;
        }
        try {
            JsonNode answer = JSON.readTree(calls.get(0).arguments()).path("finalAnswer");
            return answer.isTextual() && !answer.asText().isBlank() ? answer.asText() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void streamFinalSummary() {
        List<Message> instructions = new ArrayList<>(getMessageList());
        instructions.add(new UserMessage("请根据已观察到的工具结果，直接给出简洁的最终答复。工具失败必须明确说明，不得声称成功；不要复述工具日志、源码或内部推理。"));
        OpenAiChatOptions finalOptions = chatOptions.copy();
        finalOptions.setToolCallbacks(List.of());
        finalOptions.setToolNames(Set.of());
        finalOptions.setToolChoice("none");
        long inputChars = inputChars(instructions, false);
        long started = System.nanoTime();
        AtomicBoolean first = new AtomicBoolean();
        StringBuilder answer = new StringBuilder();
        boolean completed = false;
        try {
            // blockLast 可被工作线程 interrupt 取消订阅；不重试，避免重复输出/重复计费。
            getChatClient().prompt(new Prompt(instructions, finalOptions)).system(getSystemPrompt())
                    .stream().content().doOnNext(delta -> {
                        checkCancelled();
                        if (!delta.isEmpty()) {
                            emitAnswerDelta(delta);
                            answer.append(delta);
                            if (first.compareAndSet(false, true)) {
                                log.info("agent_model_first_delta run={} step={} phase=final first_delta_ms={}",
                                        getRunId(), getCurrentStep(), elapsedMillis(started));
                            }
                        }
                    }).blockLast();
            checkCancelled();
            if (answer.toString().isBlank()) {
                throw new IllegalStateException("模型未生成最终答复");
            }
            getMessageList().add(new AssistantMessage(answer.toString()));
            completed = true;
        } finally {
            log.info("agent_model run={} step={} phase=final completed={} elapsed_ms={} input_chars_estimate={} output_chars={}",
                    getRunId(), getCurrentStep(), completed, elapsedMillis(started), inputChars, answer.length());
        }
    }
}
