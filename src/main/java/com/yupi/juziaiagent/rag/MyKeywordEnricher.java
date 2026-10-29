package com.yupi.juziaiagent.rag;

import jakarta.annotation.Resource;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MyKeywordEnricher {

    // 原写法：@Resource
    // private ChatModel dashscopeChatModel;
    @Resource
    private OpenAiChatModel openAiChatModel;

    public List<Document> enrichDocuments(List<Document> documents) {
        // 原写法：KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(dashscopeChatModel, 5);
        KeywordMetadataEnricher keywordMetadataEnricher = new KeywordMetadataEnricher(openAiChatModel, 5);
        return keywordMetadataEnricher.apply(documents);
    }

}
