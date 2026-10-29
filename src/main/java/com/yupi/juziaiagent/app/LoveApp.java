package com.yupi.juziaiagent.app;

import com.yupi.juziaiagent.advisor.MyLoggerAdvisor;
import com.yupi.juziaiagent.config.ModelLimitsProperties;
import com.yupi.juziaiagent.advisor.ReReadingAdvisor;
import com.yupi.juziaiagent.rag.LoveAppRagCustomAdvisorFactory;
import com.yupi.juziaiagent.rag.QueryRewriter;
import jakarta.annotation.Resource;
import org.apache.catalina.core.ApplicationContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
public class LoveApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详述事情经过、对方反应及自身想法，以便给出专属解决方案。";

    /**
     * 初始化 ChatClient
     * @param openAiChatModel 使用 spring.ai.openai 配置的 OpenAI 兼容模型
     * @param chatMemory 带磁盘保留上限和上下文窗口的会话记忆
     * @param logChatContent 是否允许 DEBUG 级别记录正文，默认关闭
     * @param modelLimits 模型单次输出预算
     */
    // 原写法：public LoveApp(ChatModel dashscopeChatModel) {
    public LoveApp(OpenAiChatModel openAiChatModel,
                   @Qualifier("fileBasedChatMemory") ChatMemory chatMemory,
                   @Value("${app.chat-logging.include-content:false}") boolean logChatContent,
                   ModelLimitsProperties modelLimits) {
        // 会话存储和上下文窗口由 ChatMemoryConfig 统一配置。
        // 原写法：chatClient = ChatClient.builder(dashscopeChatModel)
        // 替换副本的默认选项，避免框架把已清空的旧 token 字段重新合并进请求。
        OpenAiChatModel boundedModel = openAiChatModel.mutate()
                .defaultOptions(modelLimits.limitedOptions(openAiChatModel)).build();
        chatClient = ChatClient.builder(boundedModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        // 默认仅记录指标；所有聊天入口共用此设置。
                        new MyLoggerAdvisor(logChatContent)
                        //自定义推理增强 Advisor ，按需开启
//                        new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI 基础对话（支持多轮对话记忆）
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {
        return chatClient.prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
    }

    /**
     * AI 流式对话，与同步调用使用相同的模型及会话记忆。
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    record LoveReport(String title, List<String> suggestions) {
    }


    /**
     * AI恋爱报告功能（实战结构化输出）
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {
        LoveReport loveReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成恋爱结果，标题为{用户名}的恋爱报告，内容为建议列表")
                .user(message)
                // 历史消息条数由 app.chat-memory.max-context-messages 控制。
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        return loveReport;
    }

    // 原写法：@Resource
    // private VectorStore loveAppVectorStore;
    // RAG 是可选能力；仅配置 Chat Completions 时，不要求存在 EmbeddingModel。
    @Autowired(required = false)
    @Qualifier("loveAppVectorStore")
    private VectorStore loveAppVectorStore;

    // 原写法：@Resource
    // private Advisor loveAppRagCloudAdvisor;
    @Autowired(required = false)
    @Qualifier("loveAppRagCloudAdvisor")
    private Advisor loveAppRagCloudAdvisor;

    // 原写法：@Resource
    // private VectorStore pgVectorVectorStore;
    @Autowired(required = false)
    @Qualifier("pgVectorVectorStore")
    private VectorStore pgVectorVectorStore;

    @Resource
    private QueryRewriter queryRewriter;


    public String doChatWithRag(String message, String chatId) {
        if (loveAppVectorStore == null) {
            throw new IllegalStateException("RAG 未启用：请先配置 EmbeddingModel，再设置 app.rag.enabled=true");
        }
        //查询重写
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);

        ChatResponse chatResponse = chatClient
                .prompt()
                //使用改写后的查询
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 应用增强检索服务（云知识库服务）
//                .advisors(loveAppRagCloudAdvisor)
                //应用RAG 检索增强服务（基于PgVector的向量存储）
                // Spring AI 1.1 起 QuestionAnswerAdvisor 改为 builder 构建
//                .advisors(QuestionAnswerAdvisor.builder(pgVectorVectorStore).build())
                .advisors(LoveAppRagCustomAdvisorFactory.createLoveAppRagCustomAdvisor(loveAppVectorStore, "单身"))
                .call()
                .chatResponse();
        String content = chatResponse.getResult().getOutput().getText();
        return content;
    }

    @Resource
    private ToolCallback[] allTools;

    public String doChatWithTools(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 原写法：.tools(allTools)
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        return content;
    }

    //AI 调用MCP服务

    // 原写法：@Resource
    // private ToolCallbackProvider toolCallbackProvider;
    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    public String doChatWithMcp(String message, String chatId) {
        if (toolCallbackProvider == null) {
            throw new IllegalStateException("MCP 客户端未启用，请先配置 MCP 服务；普通对话请调用 doChat");
        }
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                // 原写法：.tools(toolCallbackProvider)
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        return content;
    }

}
