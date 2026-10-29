package com.yupi.juziaiagent.rag;

import com.yupi.juziaiagent.constant.RagConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
class LoveAppDocumentLoader {

    /**
     * 文件名尾部的恋爱阶段标记，如「恋爱常见问题和回答 - 单身篇.md」
     */
    private static final Pattern STATUS_PATTERN = Pattern.compile("(单身|恋爱|已婚)篇?\\.md$");

    private final ResourcePatternResolver resourcePatternResolver;

    LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    //加载多篇 markdown 文档
    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            // 这里可以修改为你要加载的多个 Markdown 文件的路径模式
            Resource[] resources = resourcePatternResolver.getResources(RagConstant.DOCUMENT_LOCATION_PATTERN);
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                String status = resolveStatus(fileName);
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", fileName)
                        .withAdditionalMetadata("status", status)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }

    /**
     * 从文件名推导 status 元数据。
     * 沿用原有「……单身篇.md / ……恋爱篇.md / ……已婚篇.md」的命名约定；
     * 通用知识库文档（文件名不带阶段后缀）一律标记为 {@link RagConstant#STATUS_GENERIC}，
     * 同时避免文件名过短时按固定下标截取导致的越界异常。
     */
    private String resolveStatus(String fileName) {
        if (fileName != null) {
            Matcher matcher = STATUS_PATTERN.matcher(fileName);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return RagConstant.STATUS_GENERIC;
    }
}
