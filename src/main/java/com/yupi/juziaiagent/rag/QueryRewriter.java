package com.yupi.juziaiagent.rag;

import com.yupi.juziaiagent.config.ModelLimitsProperties;
import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

/**
 * 查询重写器
 */
@Component
public class QueryRewriter {

    private final QueryTransformer queryTransformer;

    // 原写法：public QueryRewriter(ChatModel dashscopeChatModel) {
    //     ChatClient.Builder builder = ChatClient.builder(dashscopeChatModel);
    public QueryRewriter(OpenAiChatModel openAiChatModel, ModelLimitsProperties limits) {
        OpenAiChatModel boundedModel = openAiChatModel.mutate()
                .defaultOptions(limits.limitedOptions(openAiChatModel)).build();
        ChatClient.Builder builder = ChatClient.builder(boundedModel);
        // 创建查询重写转换器
        queryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(builder)
                .build();
    }

    /**
     * 执行查询重写
     * @param prompt
     * @return
     */
    public String doQueryRewrite(String prompt) {
        Query query = new Query(prompt);
        // 执行查询重写
        Query transformedQuery = queryTransformer.transform(query);
        // 输出重写后的查询
        return transformedQuery.text();
    }
}
