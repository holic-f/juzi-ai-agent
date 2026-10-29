package com.yupi.juziaiagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileOperationToolTest {

    @TempDir
    Path temporary;

    @Test
    void readsAndReplacesAnOrdinaryFile() throws Exception {
        Path directory = temporary.resolve("files");
        FileOperationTool tool = new FileOperationTool(directory);
        assertTrue(tool.writeFile("笔记.txt", "第一版").startsWith("File written successfully"));
        assertEquals("第一版", tool.readFile("笔记.txt"));
        assertTrue(tool.writeFile("笔记.txt", "第二版").startsWith("File written successfully"));
        assertEquals("第二版", Files.readString(directory.resolve("笔记.txt")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "..\\secret.txt", "/secret.txt", "C:\\secret.txt", "C:secret.txt",
            "\\\\server\\share\\secret.txt", "file.txt:stream", "NUL", "con.txt", "COM1.txt", "LPT¹.txt",
            "nested/file.txt", "nested\\file.txt", "file.", "file ", "", ".", ".."})
    void rejectsUnsafeNamesForAllFileTools(String name) throws Exception {
        Path directory = temporary.resolve("files");
        FileOperationTool tool = new FileOperationTool(directory);
        assertTrue(tool.readFile(name).startsWith("Error reading file:"));
        assertTrue(tool.writeFile(name, "payload").startsWith("Error writing to file:"));
        assertTrue(new PDFGenerationTool(directory).generatePDF(name, "content").startsWith("Error generating PDF:"));
        assertTrue(new ResourceDownloadTool(new SafeHttpClient(), directory)
                .downloadResource("file:///must-not-be-requested", name).startsWith("Error downloading resource:"));
        assertFalse(Files.exists(directory));
    }

    @Test
    void rejectsNullNameAndLimitsLargeReads() throws Exception {
        FileOperationTool tool = new FileOperationTool(temporary);
        assertTrue(tool.readFile(null).startsWith("Error reading file:"));
        assertTrue(tool.writeFile(null, "payload").startsWith("Error writing to file:"));
        Files.write(temporary.resolve("large.txt"), new byte[1024 * 1024 + 1]);
        assertTrue(tool.readFile("large.txt").startsWith("Error reading file:"));
    }

    @Test
    void failedWriteLeavesOriginalIntactAndCleansTemporaryFile() throws Exception {
        Files.writeString(temporary.resolve("existing.txt"), "keep me");
        assertTrue(new FileOperationTool(temporary).writeFile("existing.txt", null).startsWith("Error writing to file:"));
        assertEquals("keep me", Files.readString(temporary.resolve("existing.txt")));
        try (var files = Files.list(temporary)) { assertEquals(1, files.count()); }
    }
}
