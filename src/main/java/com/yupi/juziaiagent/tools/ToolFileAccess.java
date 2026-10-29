package com.yupi.juziaiagent.tools;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** 文件工具共用的目录边界；工具参数只接受文件名，不接受路径。 */
final class ToolFileAccess {

    private final Path directory;

    ToolFileAccess(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    Path resolve(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || fileName.equals(".") || fileName.equals("..")
                || fileName.endsWith(".") || fileName.endsWith(" ")
                || fileName.chars().anyMatch(c -> c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("请只提供普通文件名，不要包含目录、盘符或特殊路径字符。");
        }
        // Windows 设备名即使带扩展名也不是普通文件。
        String baseName = fileName.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (baseName.matches("CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|COM[1-9¹²³]|LPT[1-9¹²³]")) {
            throw new IllegalArgumentException("文件名不能使用系统设备名称。");
        }
        Path target = directory.resolve(fileName).normalize();
        if (!directory.equals(target.getParent())) {
            throw new IllegalArgumentException("文件必须保存在工具指定目录内。");
        }
        checkLinks(target);
        return target;
    }

    Path createTemporaryFile() throws IOException {
        checkLinks(directory);
        Files.createDirectories(directory);
        checkLinks(directory);
        return Files.createTempFile(directory, ".tool-", ".tmp");
    }

    void replace(Path temporaryFile, String fileName) throws IOException {
        Path target = resolve(fileName);
        // 先写临时文件再替换，失败时保留原文件，也避免直接改写硬链接的目标。
        try {
            Files.move(temporaryFile, target, ATOMIC_MOVE, REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporaryFile, target, REPLACE_EXISTING);
        }
    }

    private static void checkLinks(Path target) throws IOException {
        Path current = target.getRoot();
        for (Path component : target) {
            current = current.resolve(component);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class,
                        NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()
                        || !current.toRealPath().equals(current.toRealPath(NOFOLLOW_LINKS))) {
                    throw new IOException("不允许通过链接访问文件或目录。");
                }
            }
        }
    }
}
