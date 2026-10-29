package com.yupi.juziaiagent.rag;


import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 恋爱大师 - 云知识库（阿里云百炼）RAG 顾问配置
 */
// 原写法：@Configuration（无条件依赖 DashScope 密钥）
@Configuration
@ConditionalOnProperty(name = "app.rag.cloud-enabled", havingValue = "true")
@Slf4j
class LoveAppRagCloudAdvisorConfig {

    /**
     * 百炼知识库索引名
     */
    private static final String KNOWLEDGE_INDEX = "恋爱大师";

    /* ==================== 旧写法（手动创建对象，已废弃） ====================
     * 说明：spring-ai-alibaba-dashscope 1.1.2.0 起，DashScopeApi 去掉了
     * new DashScopeApi(String apiKey) 这种便捷构造器，只能通过 Builder 创建。
     *
     * @Value("${spring.ai.dashscope.api-key}")
     * private String dashScopeApiKey;
     *
     * @Bean
     * public Advisor loveAppRagCloudAdvisor() {
     *     DashScopeApi dashScopeApi = DashScopeApi.builder()
     *             .apiKey(dashScopeApiKey)
     *             .build();
     *     DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
     *             DashScopeDocumentRetrieverOptions.builder()
     *                     .indexName(KNOWLEDGE_INDEX)
     *                     .build());
     *     return RetrievalAugmentationAdvisor.builder()
     *             .documentRetriever(documentRetriever)
     *             .build();
     * }
     * ====================================================================== */

    /**
     * 新写法：把 DashScopeApi 声明为容器管理的 Bean。
     * <p>
     * 连接信息不再自己 @Value 拼装，而是直接复用 spring-ai-alibaba-starter-dashscope
     * 已经绑定好的配置属性（spring.ai.dashscope.api-key / base-url / workspace-id / read-timeout），
     * 这样配置来源唯一，也不会写错属性名。
     * <p>
     * 如果不想依赖 starter 的配置属性类，也可以退化成：
     * <pre>
     * &#64;Bean
     * public DashScopeApi dashScopeApi(&#64;Value("${spring.ai.dashscope.api-key}") String apiKey) {
     *     return DashScopeApi.builder().apiKey(apiKey).build();
     * }
     * </pre>
     */
    @Bean
    public DashScopeApi dashScopeApi(DashScopeConnectionProperties connectionProperties) {
        return DashScopeApi.builder()
                .apiKey(connectionProperties.getApiKey())
                .baseUrl(connectionProperties.getBaseUrl())
                .workSpaceId(connectionProperties.getWorkspaceId())
                .build();
    }

    /**
     * RAG 顾问 Bean：依赖通过方法参数声明，由 Spring 自动注入容器里的 DashScopeApi，
     * 方法内部只负责组装业务对象，不再关心 DashScopeApi 是怎么来的。
     */
    @Bean
    public Advisor loveAppRagCloudAdvisor(DashScopeApi dashScopeApi) {
        DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .indexName(KNOWLEDGE_INDEX)
                        .build());
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}