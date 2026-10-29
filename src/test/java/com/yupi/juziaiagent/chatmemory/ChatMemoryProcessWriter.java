package com.yupi.juziaiagent.chatmemory;

import org.springframework.ai.chat.messages.UserMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** 跨进程离线测试的子 JVM，只向测试临时目录写入消息。 */
public class ChatMemoryProcessWriter {

    public static void main(String[] args) throws Exception {
        FileBasedChatMemory memory = new FileBasedChatMemory(args[0]);
        Files.writeString(Path.of(args[2]), "ready");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(Path.of(args[3]))) {
            if (System.nanoTime() > deadline) { throw new IllegalStateException("Test start timed out"); }
            Thread.sleep(10);
        }
        for (int i = 0; i < 20; i++) {
            memory.add("cross-process", new UserMessage(args[1] + "-" + i));
        }
    }
}
