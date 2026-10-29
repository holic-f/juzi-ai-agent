package com.yupi.juziaiagent.tools;

import com.yupi.juziaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * 文件操作工具类（提供文件读写功能）
 */
public class FileOperationTool {

    private static final int MAX_READ_BYTES = 1024 * 1024;
    private final ToolFileAccess files;

    public FileOperationTool() {
        this(Path.of(FileConstant.FILE_SAVE_DIR, "file"));
    }

    FileOperationTool(Path directory) {
        files = new ToolFileAccess(directory);
    }

    @Tool(description = "Read a UTF-8 file in the tool's file directory (up to 1 MiB). Supply a plain filename, not a path.")
    public String readFile(@ToolParam(description = "Name of the file to read") String fileName) {
        try {
            Path filePath = files.resolve(fileName);
            try (InputStream input = Files.newInputStream(filePath, NOFOLLOW_LINKS)) {
                byte[] content = input.readNBytes(MAX_READ_BYTES + 1);
                if (content.length > MAX_READ_BYTES) {
                    return "Error reading file: 文件超过读取上限（1 MiB）。";
                }
                return new String(content, StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException e) {
            return "Error reading file: 请提供普通文件名，不要包含目录或特殊路径字符。";
        } catch (IOException | SecurityException e) {
            return "Error reading file: 文件不存在、不可访问或包含不允许的链接。";
        }
    }

    @Tool(description = "Write a UTF-8 file in the tool's file directory, replacing an existing file. Supply a plain filename, not a path.")
    public String writeFile(
        @ToolParam(description = "Name of the file to write") String fileName,
        @ToolParam(description = "Content to write to the file") String content) {
        Path temporaryFile = null;
        try {
            Path filePath = files.resolve(fileName);
            temporaryFile = files.createTemporaryFile();
            Files.writeString(temporaryFile, content, StandardCharsets.UTF_8);
            files.replace(temporaryFile, fileName);
            return "File written successfully to: " + filePath;
        } catch (IllegalArgumentException e) {
            return "Error writing to file: 请提供普通文件名，不要包含目录或特殊路径字符。";
        } catch (IOException | RuntimeException e) {
            return "Error writing to file: 无法在指定目录保存文件，请检查目录权限及文件是否为链接。";
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // 保存失败已通过结果返回。
                }
            }
        }
    }
}
