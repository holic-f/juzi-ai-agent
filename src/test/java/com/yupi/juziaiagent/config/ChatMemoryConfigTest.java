package com.yupi.juziaiagent.config;

import com.yupi.juziaiagent.chatmemory.FileBasedChatMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryConfigTest {

    @TempDir
    Path directory;

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(ChatMemoryConfig.class)
                .withPropertyValues("app.chat-memory.directory=" + directory);
    }

    @Test
    void defaultConfigurationUsesTwoHundredStoredAndTwentyContextMessages() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            FileBasedChatMemory memory = context.getBean(FileBasedChatMemory.class);
            memory.add("chat", IntStream.range(0, 205)
                    .mapToObj(i -> (Message) new UserMessage("message-" + i)).toList());
            assertThat(memory.get("chat")).hasSize(20);
            assertThat(memory.get("chat", 1_000)).hasSize(200);
        });
    }

    @Test
    void configurationOverridesAreApplied() {
        contextRunner().withPropertyValues("app.chat-memory.max-stored-messages=6",
                "app.chat-memory.max-context-messages=2").run(context -> {
            assertThat(context).hasNotFailed();
            FileBasedChatMemory memory = context.getBean(FileBasedChatMemory.class);
            memory.add("chat", IntStream.range(0, 10)
                    .mapToObj(i -> (Message) new UserMessage("message-" + i)).toList());
            assertThat(memory.get("chat")).hasSize(2);
            assertThat(memory.get("chat", 100)).hasSize(6);
        });
    }

    @Test
    void invalidConfigurationFailsAtStartup() {
        contextRunner().withPropertyValues("app.chat-memory.max-stored-messages=2",
                "app.chat-memory.max-context-messages=3").run(context -> assertThat(context).hasFailed());
    }
}
