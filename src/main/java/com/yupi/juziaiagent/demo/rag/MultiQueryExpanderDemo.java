package com.yupi.juziaiagent.demo.rag;

import org.springframework.ai.chat.client.ChatClient;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.stereotype.Component;


import java.util.List;

@Component
public class MultiQueryExpanderDemo {

    private final ChatClient.Builder chatClientBuilder;

    // 原写法：public MultiQueryExpanderDemo(ChatModel dashscopeChatModel) {
    //     this.chatClientBuilder = ChatClient.builder(dashscopeChatModel);
    public MultiQueryExpanderDemo(OpenAiChatModel openAiChatModel) {
        this.chatClientBuilder = ChatClient.builder(openAiChatModel);
    }

    public List<Query> expand(String query){
        MultiQueryExpander queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .build();
        List<Query> queries = queryExpander.expand(new Query(query));
        return queries;
    }
}
