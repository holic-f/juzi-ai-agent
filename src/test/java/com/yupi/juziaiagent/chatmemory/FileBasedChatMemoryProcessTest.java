package com.yupi.juziaiagent.chatmemory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileBasedChatMemoryProcessTest {

    @TempDir
    Path temporary;

    @Test
    @Timeout(40)
    void independentJavaProcessesDoNotOverwriteEachOthersMessages() throws Exception {
        Path directory = temporary.resolve("memory");
        Path start = temporary.resolve("start");
        List<Process> workers = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                Path arguments = temporary.resolve("java-" + i + ".args");
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                // Java 参数文件避免 Windows 命令行长度限制，不经过 shell 执行。
                Files.writeString(arguments, "-cp\n" + quoted(classpath) + "\n"
                        + ChatMemoryProcessWriter.class.getName() + "\n" + quoted(directory.toString())
                        + "\nworker-" + i + "\n" + quoted(temporary.resolve("ready-" + i).toString())
                        + "\n" + quoted(start.toString()) + "\n");
                Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "@" + arguments).redirectErrorStream(true)
                        .redirectOutput(temporary.resolve("worker-" + i + ".log").toFile()).start();
                workers.add(worker);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!(Files.exists(temporary.resolve("ready-0")) && Files.exists(temporary.resolve("ready-1")))) {
                for (Process worker : workers) { assertTrue(worker.isAlive(), "Worker exited before readiness"); }
                assertTrue(System.nanoTime() < deadline, "Workers did not become ready in time");
                Thread.sleep(10);
            }
            Files.writeString(start, "go");
            for (Process worker : workers) {
                assertTrue(worker.waitFor(15, TimeUnit.SECONDS), "Worker timed out");
                assertEquals(0, worker.exitValue(), "Worker failed; inspect its temporary log");
            }
            List<String> stored = new FileBasedChatMemory(directory.toString()).get("cross-process", 200)
                    .stream().map(Message::getText).toList();
            assertEquals(40, stored.size());
            assertEquals(40, new HashSet<>(stored).size());
            for (int worker = 0; worker < 2; worker++) {
                for (int i = 0; i < 20; i++) { assertTrue(stored.contains("worker-" + worker + "-" + i)); }
            }
        } finally {
            for (Process worker : workers) {
                if (worker.isAlive()) { worker.destroyForcibly(); }
                assertTrue(worker.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
