package com.yupi.juziimagesearchmcp.config;

import com.yupi.juziimagesearchmcp.tools.ImageSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 工具注册配置
 */
@Configuration
public class ToolConfig {

    /**
     * 把标注了 @Tool 的方法注册到 MCP Server，客户端才能发现并调用这些工具
     */
    @Bean
    public ToolCallbackProvider imageSearchToolCallbackProvider(ImageSearchTool imageSearchTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(imageSearchTool)
                .build();
    }
}
