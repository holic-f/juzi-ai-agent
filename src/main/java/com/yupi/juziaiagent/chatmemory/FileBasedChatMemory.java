package com.yupi.juziaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 有界的文件会话记忆。读、追加和清空共用锁，保留旧版 .kryo 格式。
 */
public class FileBasedChatMemory implements ChatMemory {

    public static final int DEFAULT_MAX_STORED_MESSAGES = 200;
    public static final int DEFAULT_MAX_CONTEXT_MESSAGES = 20;
    // 文件操作很短，使用单一进程锁；跨平台避免多个通道关闭时影响同文件上的其他锁。
    private static final ReentrantLock FILE_OPERATIONS = new ReentrantLock();

    private final Path baseDir;
    private final int maxStoredMessages;
    private final int maxContextMessages;

    public FileBasedChatMemory(String dir) {
        this(dir, DEFAULT_MAX_STORED_MESSAGES, DEFAULT_MAX_CONTEXT_MESSAGES);
    }

    public FileBasedChatMemory(String dir, int maxStoredMessages, int maxContextMessages) {
        if (maxStoredMessages < 1 || maxContextMessages < 1 || maxContextMessages > maxStoredMessages) {
            throw new IllegalArgumentException("会话保留条数必须为正数，且上下文条数不能超过磁盘保留条数。");
        }
        this.maxStoredMessages = maxStoredMessages;
        this.maxContextMessages = maxContextMessages;
        try {
            Path directory = Path.of(dir).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            this.baseDir = directory.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话存储目录，请检查目录权限。");
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Objects.requireNonNull(messages, "消息列表不能为空。");
        if (messages.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("消息列表不能包含空消息。");
        }
        // 超大批次也只复制可能保留的尾部，避免追加阶段额外分配无界列表。
        List<Message> additions = recent(messages, maxStoredMessages);
        withConversationLock(conversationId, file -> {
            List<Message> history = readConversation(file);
            history.addAll(additions);
            saveConversation(file, recent(history, maxStoredMessages));
            return null;
        });
    }

    /**
     * Advisor 使用此方法读取历史；只返回最近的上下文窗口。
     */
    @Override
    public List<Message> get(String conversationId) {
        return get(conversationId, maxContextMessages);
    }

    /**
     * 显式读取最近 lastN 条已保留消息（最多磁盘保留上限），不修改磁盘。
     */
    public List<Message> get(String conversationId, int lastN) {
        if (lastN < 0) {
            throw new IllegalArgumentException("读取条数不能为负数。");
        }
        return withConversationLock(conversationId,
                file -> recent(readConversation(file), Math.min(lastN, maxStoredMessages)));
    }

    @Override
    public void clear(String conversationId) {
        withConversationLock(conversationId, file -> {
            Files.deleteIfExists(file);
            return null;
        });
    }

    private List<Message> readConversation(Path file) throws IOException {
        if (!Files.exists(file, NOFOLLOW_LINKS)) {
            return new ArrayList<>();
        }
        try (Input input = new Input(Files.newInputStream(file, NOFOLLOW_LINKS))) {
            // 每次操作独占一个 Kryo，不在线程之间共享它的可变序列化状态。
            List<?> decoded = newKryo().readObject(input, ArrayList.class);
            if (!input.end() || decoded.stream().anyMatch(message -> !(message instanceof Message))) {
                throw new IOException("会话文件格式不正确。");
            }
            List<Message> messages = new ArrayList<>();
            for (Object message : decoded.subList(Math.max(0, decoded.size() - maxStoredMessages), decoded.size())) {
                messages.add((Message) message);
            }
            return messages;
        }
    }

    private void saveConversation(Path file, List<Message> messages) throws IOException {
        Path temporary = Files.createTempFile(baseDir, ".chat-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, WRITE);
                 Output output = new Output(Channels.newOutputStream(channel))) {
                newKryo().writeObject(output, messages);
                output.flush();
                channel.force(true);
            }
            replaceConversationFile(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    void replaceConversationFile(Path temporary, Path target) throws IOException {
        // 不降级为直接覆盖；文件系统不支持原子替换时返回错误，保留原记录。
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING);
    }

    private <T> T withConversationLock(String conversationId, FileOperation<T> operation) {
        validateConversationId(conversationId);
        // 锁不随会话数增长。只保护短暂的磁盘操作，模型请求不持有此锁。
        FILE_OPERATIONS.lock();
        try (FileChannel channel = FileChannel.open(baseDir.resolve(".chat-memory.lock"), CREATE, WRITE,
                NOFOLLOW_LINKS);
             FileLock ignored = channel.lock()) {
            // 同目录多个 JVM 共用一个文件锁，覆盖读取、修改、保存整个过程。
            return operation.apply(baseDir.resolve(conversationId + ".kryo"));
        } catch (IOException | RuntimeException e) {
            // 不把异常堆栈或文件正文写入日志，也不把损坏文件当成空会话覆盖。
            throw new IllegalStateException("会话记录读写失败，请稍后重试或新建会话。");
        } finally {
            FILE_OPERATIONS.unlock();
        }
    }

    private static void validateConversationId(String conversationId) {
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9_-]{1,128}")
                || conversationId.toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            throw new IllegalArgumentException("会话 ID 必须为 1 至 128 个英文字母、数字、下划线或连字符，不能使用系统设备名。");
        }
    }

    private static Kryo newKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
        return kryo;
    }

    private static List<Message> recent(List<Message> messages, int limit) {
        return new ArrayList<>(messages.subList(Math.max(0, messages.size() - limit), messages.size()));
    }

    @FunctionalInterface
    private interface FileOperation<T> {
        T apply(Path file) throws IOException;
    }
}
