package com.yupi.juziaiagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 离线测试：只访问本地假 HTTP 服务，不下载外网资源。 */
class ResourceDownloadToolTest {

    @TempDir
    Path temporary;

    @Test
    void downloadsBinaryResourceAndReplacesExistingFile() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            byte[] body = {0, 1, 2, (byte) 255};
            fixture.server.createContext("/file", exchange -> {
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            ResourceDownloadTool tool = new ResourceDownloadTool(fixture.client(), temporary);
            Files.writeString(temporary.resolve("asset.bin"), "old");
            assertTrue(tool.downloadResource(fixture.url("/file"), "asset.bin")
                    .startsWith("Resource downloaded successfully to:"));
            assertArrayEquals(body, Files.readAllBytes(temporary.resolve("asset.bin")));
            try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
        }
    }

    @Test
    void rejectsTraversalBeforeSendingAnyRequest() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            AtomicInteger hits = new AtomicInteger();
            fixture.server.createContext("/file", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            String result = new ResourceDownloadTool(fixture.client(), temporary)
                    .downloadResource(fixture.url("/file"), "../escaped.bin");
            assertTrue(result.startsWith("Error downloading resource:"));
            assertEquals(0, hits.get());
            assertFalse(Files.exists(temporary.resolveSibling("escaped.bin")));
        }
    }

    @Test
    void oversizedOrTruncatedDownloadDoesNotReplaceExistingFile() throws Exception {
        try (LocalToolHttpServer fixture = new LocalToolHttpServer()) {
            fixture.server.createContext("/large", exchange -> {
                exchange.sendResponseHeaders(200, 10 * 1024 * 1024 + 1);
                exchange.close();
            });
            fixture.server.createContext("/truncated", exchange -> {
                exchange.sendResponseHeaders(200, 100);
                try (var output = exchange.getResponseBody()) { output.write(new byte[2]); }
            });
            fixture.server.createContext("/error", exchange -> {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            });
            ResourceDownloadTool tool = new ResourceDownloadTool(fixture.client(), temporary);
            Path output = temporary.resolve("asset.bin");
            Files.writeString(output, "keep me");
            for (String path : new String[]{"/large", "/truncated", "/error"}) {
                assertTrue(tool.downloadResource(fixture.url(path), "asset.bin").startsWith("Error downloading resource:"));
                assertEquals("keep me", Files.readString(output));
            }
            try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
        }
    }

    @Test
    void productionPolicyRejectsLocalResourceWithoutCreatingAFile() throws Exception {
        ResourceDownloadTool tool = new ResourceDownloadTool(new SafeHttpClient(), temporary);
        assertTrue(tool.downloadResource("http://127.0.0.1/", "asset.bin")
                .startsWith("Error downloading resource:"));
        assertFalse(Files.exists(temporary.resolve("asset.bin")));
    }
}
