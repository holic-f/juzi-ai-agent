package com.yupi.juziaiagent.tools;

import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.yupi.juziaiagent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PDFGenerationTool {

    private final ToolFileAccess files;

    public PDFGenerationTool() {
        this(Path.of(FileConstant.FILE_SAVE_DIR, "pdf"));
    }

    PDFGenerationTool(Path directory) {
        files = new ToolFileAccess(directory);
    }

    private static final String FONT_PATH_PROPERTY = "juzi.pdf.font.path";
    private static final String[] FONT_PATHS = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/NotoSansSC-Regular.ttf",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
            "/System/Library/Fonts/STHeiti Light.ttc"
    };

    @Tool(description = "Generate a PDF in the pdf directory, replacing an existing file. Supply a plain filename, not a path.")
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Content to be included in the PDF") String content) {
        Path temporaryFile = null;
        try {
            Path filePath = files.resolve(fileName);
            PdfFont font = loadFont(content);
            temporaryFile = files.createTemporaryFile();
            try (Document document = new Document(new PdfDocument(new PdfWriter(temporaryFile.toString())))) {
                document.setFont(font);
                document.add(new Paragraph(content));
            }
            files.replace(temporaryFile, fileName);
            return "PDF generated successfully to: " + filePath;
        } catch (IOException | RuntimeException e) {
            return "Error generating PDF: 请使用普通文件名，并检查字体配置、目录权限及文件是否为链接。";
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // The error result above already reports the generation failure.
                }
            }
        }
    }

    private PdfFont loadFont(String content) throws IOException {
        String configuredPath = System.getProperty(FONT_PATH_PROPERTY);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return createFont(configuredPath, content);
        }
        for (String fontPath : FONT_PATHS) {
            if (Files.isRegularFile(Paths.get(fontPath))) {
                try {
                    return createFont(fontPath, content);
                } catch (IOException | RuntimeException ignored) {
                    // Try the next installed font.
                }
            }
        }
        throw new IOException("No usable Chinese font found; set " + FONT_PATH_PROPERTY + " to a Chinese font file");
    }

    private PdfFont createFont(String fontPath, String content) throws IOException {
        PdfFont font = PdfFontFactory.createFont(fontPath, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
        if (content != null && content.codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
                .anyMatch(codePoint -> !font.containsGlyph(codePoint))) {
            throw new IOException("Font does not support all content characters: " + fontPath);
        }
        return font;
    }
}
