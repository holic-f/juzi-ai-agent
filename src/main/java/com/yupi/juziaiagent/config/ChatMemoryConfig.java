package com.yupi.juziaiagent.config;

import com.yupi.juziaiagent.chatmemory.FileBasedChatMemory;
import com.yupi.juziaiagent.constant.FileConstant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryConfig {

    @Bean
    public FileBasedChatMemory fileBasedChatMemory(
            @Value("${app.chat-memory.directory:}") String directory,
            @Value("${app.chat-memory.max-stored-messages:200}") int maxStoredMessages,
            @Value("${app.chat-memory.max-context-messages:20}") int maxContextMessages) {
        String location = directory.isBlank() ? FileConstant.FILE_SAVE_DIR + "/chat-memory" : directory;
        return new FileBasedChatMemory(location, maxStoredMessages, maxContextMessages);
    }
}
