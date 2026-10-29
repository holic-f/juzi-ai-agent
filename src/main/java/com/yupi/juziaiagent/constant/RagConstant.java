package com.yupi.juziaiagent.constant;

/**
 * RAG 文档元数据约定。
 */
public interface RagConstant {

    /**
     * 与恋爱阶段无关的通用知识库文档所使用的 status 值。
     * 文件名不符合「单身篇 / 恋爱篇 / 已婚篇」约定的文档都会落到这个值上，
     * 检索时应与用户当前所处阶段一起参与过滤。
     */
    String STATUS_GENERIC = "通用";

    /**
     * document 目录下的 markdown 文件匹配模式。
     */
    String DOCUMENT_LOCATION_PATTERN = "classpath:document/*.md";
}