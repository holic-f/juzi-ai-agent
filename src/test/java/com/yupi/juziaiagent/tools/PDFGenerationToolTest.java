package com.yupi.juziaiagent.tools;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDFGenerationToolTest {

    @TempDir
    Path directory;

    @Test
    void generatesReadableChinesePdf() throws Exception {
        Path output = outputPath();
        try {
            String content = "编程导航原创项目 https://www.codefather.cn";
            String result = new PDFGenerationTool(directory).generatePDF(output.getFileName().toString(), content);
            assertTrue(result.startsWith("PDF generated successfully to: "), result);
            assertTrue(Files.size(output) > 0);
            try (PdfDocument pdf = new PdfDocument(new PdfReader(output.toString()))) {
                assertTrue(PdfTextExtractor.getTextFromPage(pdf.getPage(1)).contains(content));
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void fontFailureDoesNotCreateOrReplacePdf() throws Exception {
        Path output = outputPath();
        String previousFont = System.getProperty("juzi.pdf.font.path");
        try {
            System.setProperty("juzi.pdf.font.path", output.resolveSibling("missing-font-" + UUID.randomUUID() + ".ttf").toString());
            String result = new PDFGenerationTool(directory).generatePDF(output.getFileName().toString(), "中文");
            assertTrue(result.startsWith("Error generating PDF: "), result);
            assertFalse(Files.exists(output));

            byte[] existing = "existing PDF content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(output, existing);
            result = new PDFGenerationTool(directory).generatePDF(output.getFileName().toString(), "中文");
            assertTrue(result.startsWith("Error generating PDF: "), result);
            assertArrayEquals(existing, Files.readAllBytes(output));
        } finally {
            if (previousFont == null) {
                System.clearProperty("juzi.pdf.font.path");
            } else {
                System.setProperty("juzi.pdf.font.path", previousFont);
            }
            Files.deleteIfExists(output);
        }
    }

    private Path outputPath() {
        return directory.resolve("pdf-test-" + UUID.randomUUID() + ".pdf");
    }
}
