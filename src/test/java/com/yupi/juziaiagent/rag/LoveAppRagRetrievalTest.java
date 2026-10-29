package com.yupi.juziaiagent.rag;

import com.yupi.juziaiagent.constant.RagConstant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通用知识库入库 + 阶段过滤检索的离线校验。
 * <p>
 * 用确定性的假 EmbeddingModel 替代真实向量服务，不调用模型接口、不依赖数据库。
 */
class LoveAppRagRetrievalTest {

    private static final Set<String> STAGE_STATUSES = Set.of("单身", "恋爱", "已婚");

    @Test
    void genericKnowledgeBase_shouldBeRetrievableForStageQuery() {
        LoveAppDocumentLoader loader = new LoveAppDocumentLoader(new PathMatchingResourcePatternResolver());
        List<Document> documents = loader.loadMarkdowns();
        assertFalse(documents.isEmpty(), "语料为空");

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(new StubEmbeddingModel()).build();
        vectorStore.add(documents);

        // 与 LoveAppRagCustomAdvisorFactory 使用相同的过滤条件
        Filter.Expression expression = new FilterExpressionBuilder()
                .in("status", "单身", RagConstant.STATUS_GENERIC)
                .build();
        List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
                .query("对方忽冷忽热，我该怎么判断是回避型还是没兴趣")
                .filterExpression(expression)
                .similarityThreshold(SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL)
                .topK(5)
                .build());

        assertFalse(results.isEmpty(), "按 status 过滤后检索不到任何文档，通用知识库未生效");
        assertTrue(results.stream().allMatch(document -> {
            Object status = document.getMetadata().get("status");
            return RagConstant.STATUS_GENERIC.equals(status) || STAGE_STATUSES.contains(status);
        }), "检索结果包含未通过 status 过滤的文档");
        assertTrue(results.stream().anyMatch(document ->
                        RagConstant.STATUS_GENERIC.equals(document.getMetadata().get("status"))),
                "检索结果没有命中通用知识库文档");
    }

    /**
     * 确定性假向量模型：按字符散列成 64 维词袋向量，保证同一文本向量稳定。
     */
    static class StubEmbeddingModel implements EmbeddingModel {

        private static final int DIMENSIONS = 64;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(vectorize(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorize(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        private float[] vectorize(String text) {
            float[] vector = new float[DIMENSIONS];
            if (text == null) {
                return vector;
            }
            for (int i = 0; i < text.length(); i++) {
                vector[Math.floorMod(text.charAt(i) * 31 + i, DIMENSIONS)] += 1f;
            }
            double norm = 0;
            for (float value : vector) {
                norm += value * value;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) (vector[i] / norm);
                }
            }
            return vector;
        }
    }
}