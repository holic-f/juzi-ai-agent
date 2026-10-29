package com.yupi.juziaiagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ToolFileAccessTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsLinkedToolDirectoryAndLinkedAncestor() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "keep secret");
        Files.createDirectory(outside.resolve("nested"));
        Path link = temporary.resolve("link");
        createDirectoryLink(link, outside);
        try {
            FileOperationTool direct = new FileOperationTool(link);
            assertTrue(direct.readFile("secret.txt").startsWith("Error reading file:"));
            assertTrue(direct.writeFile("secret.txt", "overwrite").startsWith("Error writing to file:"));
            assertTrue(new FileOperationTool(link.resolve("nested")).writeFile("new.txt", "escape")
                    .startsWith("Error writing to file:"));
            assertTrue(new PDFGenerationTool(link).generatePDF("secret.txt", "overwrite")
                    .startsWith("Error generating PDF:"));
            assertTrue(new ResourceDownloadTool(new SafeHttpClient(), link)
                    .downloadResource("http://127.0.0.1/", "secret.txt")
                    .startsWith("Error downloading resource:"));
            assertEquals("keep secret", Files.readString(outside.resolve("secret.txt")));
            assertFalse(Files.exists(outside.resolve("nested/new.txt")));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void rejectsLinkedTargetBeforeWritingOrReading() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path directory = Files.createDirectory(temporary.resolve("files"));
        Path link = directory.resolve("linked");
        createDirectoryLink(link, outside);
        try {
            FileOperationTool tool = new FileOperationTool(directory);
            assertTrue(tool.readFile("linked").startsWith("Error reading file:"));
            assertTrue(tool.writeFile("linked", "escape").startsWith("Error writing to file:"));
            assertThrows(IOException.class, () -> new ToolFileAccess(directory).resolve("linked"));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    private void createDirectoryLink(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Files.createSymbolicLink(link, target);
            return;
        }
        // Windows junction 不需要管理员权限。路径通过环境变量传递，避免拼接 shell 代码。
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference = 'Stop'; New-Item -ItemType Junction -Path $env:JUZI_TEST_LINK "
                        + "-Target $env:JUZI_TEST_TARGET | Out-Null");
        builder.environment().put("JUZI_TEST_LINK", link.toString());
        builder.environment().put("JUZI_TEST_TARGET", target.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Creating temporary junction timed out");
        }
        assertEquals(0, process.exitValue(), "Creating temporary junction failed");
        assertTrue(Files.exists(link));
    }
}
