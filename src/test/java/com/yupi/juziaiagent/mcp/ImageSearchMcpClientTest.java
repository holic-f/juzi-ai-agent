package com.yupi.juziaiagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.StdioTransportAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 离线安全：仅连接本地假 MCP 服务，不加载本地密钥、不调用模型或图片 API。 */
class ImageSearchMcpClientTest {

    @Test
    @Timeout(30)
    void discoversAndCallsImageToolUsingProductionProfile() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CompletableFuture<OutputStream> events = new CompletableFuture<>();
        AtomicReference<JsonNode> toolArguments = new AtomicReference<>();
        var executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/sse", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream output = exchange.getResponseBody();
            output.write("event: endpoint\ndata: /message\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            events.complete(output);
        });
        server.createContext("/message", exchange -> {
            try {
                JsonNode request = json.readTree(exchange.getRequestBody());
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                if (!request.has("id")) {
                    return;
                }
                Object result = switch (request.path("method").asText()) {
                    case "initialize" -> Map.of(
                            "protocolVersion", request.path("params").path("protocolVersion").asText(),
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "offline-image-search", "version", "1.0"));
                    case "tools/list" -> Map.of("tools", List.of(Map.of(
                            "name", "searchImage",
                            "description", "Search images",
                            "inputSchema", Map.of("type", "object", "properties",
                                    Map.of("query", Map.of("type", "string")),
                                    "required", List.of("query")))));
                    case "tools/call" -> {
                        toolArguments.set(request.path("params"));
                        yield Map.of("content", List.of(Map.of("type", "text",
                                "text", "https://example.test/computer.jpg")), "isError", false);
                    }
                    default -> Map.of();
                };
                String response = json.writeValueAsString(Map.of(
                        "jsonrpc", "2.0", "id", request.get("id"), "result", result));
                OutputStream output = events.get(5, TimeUnit.SECONDS);
                synchronized (output) {
                    output.write(("event: message\ndata: " + response + "\n\n")
                            .getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (Exception e) {
                throw new IOException("Local MCP fixture failed", e);
            }
        });
        server.start();

        try {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withPropertyValues(
                            "spring.config.location=classpath:/application-mcp-image.yml",
                            "spring.ai.mcp.client.sse.connections.juzi-image-search.url=http://127.0.0.1:"
                                    + server.getAddress().getPort(),
                            "spring.ai.mcp.client.request-timeout=5s")
                    .withConfiguration(AutoConfigurations.of(
                            StdioTransportAutoConfiguration.class,
                            SseHttpClientTransportAutoConfiguration.class,
                            McpClientAutoConfiguration.class,
                            McpToolCallbackAutoConfiguration.class))
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(McpStdioClientProperties.class).toServerParameters()).isEmpty();
                        var tools = context.getBean(ToolCallbackProvider.class).getToolCallbacks();
                        assertThat(tools).hasSize(1);
                        assertThat(tools[0].getToolDefinition().name()).contains("searchImage");
                        assertThat(tools[0].call("{\"query\":\"computer\"}"))
                                .contains("https://example.test/computer.jpg");
                        assertThat(toolArguments.get().path("name").asText()).isEqualTo("searchImage");
                        assertThat(toolArguments.get().path("arguments").path("query").asText())
                                .isEqualTo("computer");
                    });
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
