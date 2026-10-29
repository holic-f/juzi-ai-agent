package com.yupi.juziaiagent.agent.model;

import com.yupi.juziaiagent.config.ModelLimitsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * JuziManus
 */
@Component
public class JuziManus extends ToolCallAgent {

    // 原写法：public JuziManus(ToolCallback[] allTools, ChatModel dashscopeChatModel) {
    public JuziManus(ToolCallback[] allTools, OpenAiChatModel openAiChatModel) {
        this(allTools, openAiChatModel, new ModelLimitsProperties());
    }

    @Autowired
    public JuziManus(ToolCallback[] allTools, OpenAiChatModel openAiChatModel, ModelLimitsProperties limits) {
        super(allTools);
        // 每次请求复制参数，不修改共享模型；保留关闭框架自动执行工具的既有约定。
        var bounded = limits.limitedManusOptions(openAiChatModel);
        getChatOptions().setMaxTokens(bounded.getMaxTokens());
        getChatOptions().setMaxCompletionTokens(bounded.getMaxCompletionTokens());
        this.setName("juziManus");
        this.setSystemPrompt("""
                你是 JuziManus，一名能够按需使用工具完成用户任务的 AI 助手。
                默认使用简体中文与用户交流，最终答复、执行结果说明及生成文档的正文均使用简体中文；仅当用户明确要求其他语言时才切换。
                工具返回英文不影响答复语言。代码、工具名称、文件路径、网址及必要的专有名词保留原样。
                工具结果仅供内部参考，不要向用户复述原始 HTML、工具日志或内部推理。
                最终答复应简洁地说明实际结果。文件操作失败时必须如实说明，不得声称已经成功。
                """);
        this.setNextStepPrompt("""
                根据用户任务选择必要的工具。任务完成后，直接用简体中文给出最终答复而不再调用工具，
                不要为结束任务而额外调用工具。确需调用 doTerminate 时，携带已验证的简洁 finalAnswer，避免再次总结。
                finalAnswer 只依据已经观察到的工具结果，不要与尚未执行的其它工具放在同一批调用。
                若用户明确指定其他语言，则遵循用户要求。工具失败后，不要在未改变方法的情况下反复重试。
                """);
        this.setMaxSteps(limits.getManusMaxSteps());
        // 原写法：ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
        // Agent 自己记录耗时/大小指标，避免完整内容日志增加开销及泄露工具数据。
        OpenAiChatModel boundedModel = openAiChatModel.mutate().defaultOptions(bounded).build();
        ChatClient chatClient = ChatClient.builder(boundedModel).build();
        this.setChatClient(chatClient);
    }
}
