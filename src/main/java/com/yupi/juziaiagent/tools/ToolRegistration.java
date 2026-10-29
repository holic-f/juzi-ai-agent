package com.yupi.juziaiagent.tools;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class ToolRegistration {

    @Value("${search-api.api-key}")
    private String searchApiKey;

    @Bean
    public ToolCallback[] allTools(
            @Value("${app.tools.terminal.enabled:false}") boolean terminalEnabled) {
        List<Object> tools = new ArrayList<>(List.of(
                new FileOperationTool(),
                new WebSearchTool(searchApiKey),
                new WebScrapingTool(),
                new ResourceDownloadTool(),
                new TerminateTool(),
                new PDFGenerationTool()
        ));
        if (terminalEnabled) {
            tools.add(new TerminalOperationTool());
        }
        return ToolCallbacks.from(tools.toArray());
    }
}
