package com.yupi.juziaiagent.tools;

import com.yupi.juziaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ResourceDownloadTool {

    private static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;
    private final SafeHttpClient http;
    private final ToolFileAccess files;

    public ResourceDownloadTool() {
        this(new SafeHttpClient(), Path.of(FileConstant.FILE_SAVE_DIR, "download"));
    }

    ResourceDownloadTool(SafeHttpClient http, Path directory) {
        this.http = http;
        files = new ToolFileAccess(directory);
    }

    @Tool(description = "Download a public HTTP/HTTPS resource (up to 10 MiB) into the download directory, replacing an existing file. Supply a plain filename, not a path. Local/private addresses are forbidden.")
    public String downloadResource(@ToolParam(description = "URL of the resource to download") String url, @ToolParam(description = "Name of the file to save the downloaded resource") String fileName) {
        Path temporaryFile = null;
        try {
            Path filePath = files.resolve(fileName);
            SafeHttpClient.Response response = http.get(url, MAX_DOWNLOAD_BYTES);
            temporaryFile = files.createTemporaryFile();
            Files.write(temporaryFile, response.body());
            files.replace(temporaryFile, fileName);
            return "Resource downloaded successfully to: " + filePath;
        } catch (IllegalArgumentException e) {
            return "Error downloading resource: 请提供普通文件名，不要包含目录或特殊路径字符。";
        } catch (IOException | RuntimeException e) {
            return "Error downloading resource: 下载失败；仅支持公网 HTTP/HTTPS、最大 10 MiB，且必须保存到无链接的指定目录。";
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // 下载失败已通过结果返回。
                }
            }
        }
    }
}
