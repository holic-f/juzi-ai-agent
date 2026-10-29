package com.yupi.juziaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class FileBasedChatMemoryTest {

    @TempDir
    Path directory;

    @Test
    void messagesSurviveReloadAndReturnedListsAreIndependentSnapshots() {
        FileBasedChatMemory memory = memory();
        assertTrue(memory.get("chat").isEmpty());
        memory.add("chat", List.of(new UserMessage("问题"), new AssistantMessage("回答")));
        FileBasedChatMemory reloaded = memory();
        List<Message> snapshot = reloaded.get("chat");
        assertEquals(List.of("问题", "回答"), texts(snapshot));
        assertInstanceOf(UserMessage.class, snapshot.get(0));
        assertInstanceOf(AssistantMessage.class, snapshot.get(1));
        snapshot.clear();
        assertEquals(List.of("问题", "回答"), texts(reloaded.get("chat")));
        reloaded.clear("chat");
        reloaded.clear("chat");
        assertTrue(memory.get("chat").isEmpty());
    }

    @Test
    void diskKeepsTwoHundredMessagesAndDefaultContextUsesOnlyTwenty() throws Exception {
        FileBasedChatMemory memory = memory();
        memory.add("chat", messages(0, 205));
        assertEquals(texts(messages(185, 205)), texts(memory.get("chat")));
        assertEquals(texts(messages(5, 205)), texts(memory.get("chat", 1_000)));
        try (Input input = new Input(Files.newInputStream(directory.resolve("chat.kryo")))) {
            assertEquals(200, legacyKryo().readObject(input, ArrayList.class).size());
        }
        memory.add("chat", new AssistantMessage("latest"));
        assertEquals(200, memory.get("chat", 200).size());
        assertEquals("message-6", memory.get("chat", 200).get(0).getText());
        assertEquals("latest", memory.get("chat").get(19).getText());
    }

    @Test
    void supportsExplicitLimitsAndRejectsInvalidConfiguration() {
        FileBasedChatMemory memory = new FileBasedChatMemory(directory.toString(), 6, 2);
        memory.add("chat", messages(0, 8));
        assertEquals(List.of("message-6", "message-7"), texts(memory.get("chat")));
        assertEquals(6, memory.get("chat", 100).size());
        assertTrue(memory.get("chat", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> memory.get("chat", -1));
        assertThrows(IllegalArgumentException.class, () -> new FileBasedChatMemory(directory.toString(), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileBasedChatMemory(directory.toString(), 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new FileBasedChatMemory(directory.toString(), 2, 3));
    }

    @Test
    void oldKryoFilesRemainReadableAndArePrunedOnlyOnNextSave() throws Exception {
        Path file = directory.resolve("legacy.kryo");
        try (Output output = new Output(Files.newOutputStream(file))) {
            legacyKryo().writeObject(output, messages(0, 250));
        }
        byte[] original = Files.readAllBytes(file);
        FileBasedChatMemory memory = memory();
        assertEquals(texts(messages(230, 250)), texts(memory.get("legacy")));
        assertArrayEquals(original, Files.readAllBytes(file), "Reads must not rewrite old files");
        memory.add("legacy", new UserMessage("new"));
        assertEquals(200, memory.get("legacy", 1_000).size());
        assertEquals("message-51", memory.get("legacy", 200).get(0).getText());
        assertEquals("new", memory.get("legacy").get(19).getText());
    }

    @Test
    void simultaneousAppendsAcrossInstancesDoNotLoseMessages() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                int number = worker;
                work.add(executor.submit(() -> {
                    FileBasedChatMemory memory = memory();
                    start.await();
                    for (int i = 0; i < 10; i++) {
                        memory.add("shared", new UserMessage(number + "-" + i));
                        assertFalse(memory.get("shared").isEmpty());
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : work) { future.get(20, TimeUnit.SECONDS); }
            List<String> stored = texts(memory().get("shared", 200));
            assertEquals(80, stored.size());
            assertEquals(80, new HashSet<>(stored).size());
            for (int worker = 0; worker < 8; worker++) {
                for (int i = 0; i < 10; i++) { assertTrue(stored.contains(worker + "-" + i)); }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void differentConversationsCanSerializeWithoutSharingKryoState() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                String id = "conversation-" + worker;
                work.add(executor.submit(() -> {
                    start.await();
                    FileBasedChatMemory memory = memory();
                    for (int i = 0; i < 10; i++) { memory.add(id, new UserMessage(id + "-" + i)); }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : work) { future.get(20, TimeUnit.SECONDS); }
            for (int worker = 0; worker < 8; worker++) {
                String id = "conversation-" + worker;
                assertEquals(IntStream.range(0, 10).mapToObj(i -> id + "-" + i).toList(), texts(memory().get(id)));
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void clearWaitsForAnInFlightSaveAndDoesNotResurrectOldHistory() throws Exception {
        memory().add("chat", new UserMessage("old"));
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        CountDownLatch clearing = new CountDownLatch(1);
        FileBasedChatMemory delayed = new FileBasedChatMemory(directory.toString()) {
            @Override
            void replaceConversationFile(Path temporary, Path target) throws IOException {
                saving.countDown();
                try {
                    if (!releaseSave.await(5, TimeUnit.SECONDS)) { throw new IOException("Test deadline"); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test interrupted");
                }
                super.replaceConversationFile(temporary, target);
            }
        };
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> delayed.add("chat", new UserMessage("new")));
            assertTrue(saving.await(5, TimeUnit.SECONDS));
            Future<?> clear = executor.submit(() -> {
                clearing.countDown();
                memory().clear("chat");
            });
            assertTrue(clearing.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> clear.get(100, TimeUnit.MILLISECONDS));
            releaseSave.countDown();
            writer.get(5, TimeUnit.SECONDS);
            clear.get(5, TimeUnit.SECONDS);
            assertTrue(memory().get("chat").isEmpty());
        } finally {
            releaseSave.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failedAtomicReplacePreservesOriginalFileAndRemovesTemporaryFile() throws Exception {
        memory().add("chat", new UserMessage("keep me"));
        byte[] original = Files.readAllBytes(directory.resolve("chat.kryo"));
        FileBasedChatMemory failing = new FileBasedChatMemory(directory.toString()) {
            @Override
            void replaceConversationFile(Path temporary, Path target) throws IOException {
                throw new IOException("Simulated disk failure with private detail");
            }
        };
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> failing.add("chat", new UserMessage("new")));
        assertFalse(error.getMessage().contains("private detail"));
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("chat.kryo")));
        assertEquals(List.of("keep me"), texts(memory().get("chat")));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void corruptFileProducesAnErrorInsteadOfBeingSilentlyOverwritten() throws Exception {
        byte[] corrupt = {1, 2, 3};
        Path file = directory.resolve("broken.kryo");
        Files.write(file, corrupt);
        assertThrows(IllegalStateException.class, () -> memory().get("broken"));
        assertThrows(IllegalStateException.class, () -> memory().add("broken", new UserMessage("new")));
        assertArrayEquals(corrupt, Files.readAllBytes(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../outside", "..\\outside", "C:outside", "NUL", "con", "COM1", "a/b", "a:b"})
    void rejectsUnsafeConversationIdsForAllOperations(String id) {
        assertThrows(IllegalArgumentException.class, () -> memory().get(id));
        assertThrows(IllegalArgumentException.class, () -> memory().add(id, new UserMessage("message")));
        assertThrows(IllegalArgumentException.class, () -> memory().clear(id));
    }

    @Test
    void validatesNullArguments() {
        FileBasedChatMemory memory = memory();
        assertThrows(IllegalArgumentException.class, () -> memory.get(null));
        assertThrows(NullPointerException.class, () -> memory.add("chat", (List<Message>) null));
        assertThrows(IllegalArgumentException.class,
                () -> memory.add("chat", java.util.Arrays.asList(new UserMessage("valid"), null)));
    }

    private FileBasedChatMemory memory() {
        return new FileBasedChatMemory(directory.toString());
    }

    private static ArrayList<Message> messages(int from, int to) {
        return new ArrayList<>(IntStream.range(from, to)
                .mapToObj(i -> (Message) new UserMessage("message-" + i)).toList());
    }

    private static List<String> texts(List<Message> messages) {
        return messages.stream().map(Message::getText).toList();
    }

    private static Kryo legacyKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        return kryo;
    }
}
