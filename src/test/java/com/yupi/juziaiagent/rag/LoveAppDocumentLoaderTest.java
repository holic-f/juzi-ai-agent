package com.yupi.juziaiagent.rag;

import com.yupi.juziaiagent.constant.RagConstant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * document 目录语料加载的离线校验：只读本地资源，不调用模型、不写向量库。
 */
class LoveAppDocumentLoaderTest {

    /**
     * 阶段问答文档的 status 取值
     */
    private static final Set<String> STAGE_STATUSES = Set.of("单身", "恋爱", "已婚");

    @Test
    void loadMarkdowns_shouldTagEveryDocumentWithKnownStatus() {
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver());
        List<Document> documents = loader.loadMarkdowns();

        assertFalse(documents.isEmpty(), "document 目录下应至少解析出一篇文档");

        int genericCount = 0;
        for (Document document : documents) {
            String fileName = String.valueOf(document.getMetadata().get("filename"));
            Object status = document.getMetadata().get("status");
            assertNotNull(document.getMetadata().get("filename"), "文档缺少 filename 元数据");
            assertNotNull(status, "文档缺少 status 元数据：" + fileName);
            assertTrue(STAGE_STATUSES.contains(status) || RagConstant.STATUS_GENERIC.equals(status),
                    "无法识别的 status：" + status + "（" + fileName + "）");
            assertFalse(document.getText() == null || document.getText().isBlank(),
                    "文档正文为空：" + fileName);
            if (RagConstant.STATUS_GENERIC.equals(status)) {
                genericCount++;
            }
        }

        // 通用知识库必须被加载，否则 RAG 只会命中三份阶段问答文档
        assertTrue(genericCount > 0, "未加载到任何通用知识库文档（status=" + RagConstant.STATUS_GENERIC + "）");
    }

    @Test
    void loadMarkdowns_shouldKeepStageStatusFromFileName() {
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver());
        List<Document> documents = loader.loadMarkdowns();

        // 「恋爱常见问题和回答 - 恋爱篇.md」这类原有文件仍应解析出对应阶段
        boolean hasStageDocument = documents.stream()
                .anyMatch(document -> STAGE_STATUSES.contains(document.getMetadata().get("status")));
        assertTrue(hasStageDocument, "原有阶段问答文档的 status 解析失败");
    }

    @Test
    void loadMarkdowns_shouldSplitByHorizontalRule() {
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver());
        List<Document> documents = loader.loadMarkdowns();

        // 单个切片不应过大，否则向量检索精度会明显下降
        long oversized = documents.stream()
                .filter(document -> document.getText().length() > 3000)
                .count();
        assertEquals(0, oversized, "存在超过 3000 字符的切片，请检查语料切片粒度");
    }
}