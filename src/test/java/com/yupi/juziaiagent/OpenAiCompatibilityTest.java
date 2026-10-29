package com.yupi.juziaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yupi.juziaiagent.agent.model.JuziManus;
import com.yupi.juziaiagent.chatmemory.FileBasedChatMemory;
import com.yupi.juziaiagent.advisor.MyLoggerAdvisor;
import com.yupi.juziaiagent.app.LoveApp;
import com.yupi.juziaiagent.config.ModelLimitsProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full application HTTP tests with a loopback-only OpenAI-compatible gateway.
 * The isolated config location prevents application.yml/application-local.yml
 * (including real credentials and MCP connection definitions) from being loaded.
 * Run explicitly together with AgentFlowTest; no external services are required.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/openai-compatibility-test/",
        "spring.profiles.active=test",
        "server.address=127.0.0.1",
        "server.max-http-request-header-size=64KB",
        "server.servlet.context-path=/api",
        "spring.ai.openai.api-key=test-key",
        "spring.ai.openai.chat.completions-path=/v1/chat/completions",
        "spring.ai.openai.chat.options.model=test-model",
        "app.agent.model=fast-test-model",
        "app.agent.max-tool-result-chars=1024",
        "spring.ai.model.chat=openai",
        "spring.ai.model.embedding=none",
        "spring.ai.dashscope.enabled=false",
        "spring.ai.mcp.client.enabled=false",
        "app.rag.enabled=false",
        "app.rag.cloud-enabled=false",
        "app.rag.pgvector-enabled=false",
        "app.ai.demo-enabled=false",
        "search-api.api-key=test-key",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class OpenAiCompatibilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BlockingQueue<GatewayRequest> REQUESTS = new LinkedBlockingQueue<>();
    private static final String SYNC_ANSWER = "offline-sync-answer";
    private static final String STREAM_ANSWER = "offline-stream-answer";
    private static final String TOOL_NAME = "compatibilityCounter";
    private static final Path MEMORY_DIRECTORY = temporaryMemoryDirectory();
    private static final HttpServer GATEWAY = startGateway();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<String> conversationIds = new ArrayList<>();

    @LocalServerPort
    private int port;

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @Autowired
    private FileBasedChatMemory chatMemory;

    @DynamicPropertySource
    static void localOpenAiProperties(DynamicPropertyRegistry registry) {
        registry.add("app.chat-memory.directory", MEMORY_DIRECTORY::toString);
        registry.add("spring.ai.openai.base-url",
                () -> "http://127.0.0.1:" + GATEWAY.getAddress().getPort());
    }

    @BeforeEach
    void resetRequests() {
        REQUESTS.clear();
    }

    @AfterEach
    void removeOnlyThisTestsConversationFiles() {
        conversationIds.forEach(chatMemory::clear);
    }

    @AfterAll
    static void stopGateway() throws IOException {
        GATEWAY.stop(0);
        try (var files = Files.list(MEMORY_DIRECTORY)) {
            for (Path file : files.toList()) { Files.deleteIfExists(file); }
        }
        Files.deleteIfExists(MEMORY_DIRECTORY);
    }

    private static Path temporaryMemoryDirectory() {
        try {
            return Files.createTempDirectory("juzi-memory-compatibility-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void defaultSyncAndSseDoNotLogChatBodiesEvenAtDebugLevel() throws Exception {
        Logger advisorLogger = (Logger) LoggerFactory.getLogger(MyLoggerAdvisor.class);
        Logger appLogger = (Logger) LoggerFactory.getLogger(LoveApp.class);
        Level previousLevel = advisorLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        appender.start();
        advisorLogger.addAppender(appender);
        appLogger.addAppender(appender);
        advisorLogger.setLevel(Level.DEBUG);
        String privateInput = "offline-private-relationship-message";
        try {
            HttpResponse<String> sync = get("/ai/love_app/chat/sync", privateInput, newChatId(), false);
            assertEquals(200, sync.statusCode());
            assertEquals(SYNC_ANSWER, sync.body());
            takeRequest(false);
            HttpResponse<String> sse = get("/ai/love_app/chat/sse", privateInput, newChatId(), true);
            assertEquals(200, sse.statusCode());
            String eventText = sse.body().lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).stripLeading())
                    .collect(Collectors.joining());
            assertEquals(STREAM_ANSWER, eventText, sse.body());
            takeRequest(true);
            List<String> logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(logs.stream().anyMatch(line -> line.contains("mode=call status=complete")));
            for (String line : logs) {
                assertFalse(line.contains(privateInput), line);
                assertFalse(line.contains(SYNC_ANSWER), line);
                assertFalse(line.contains(STREAM_ANSWER), line);
                assertFalse(line.contains("offline-"), line);
                assertFalse(line.contains("stream-answer"), line);
            }
            assertTrue(REQUESTS.isEmpty(), "日志不得增加模型调用");
        } finally {
            advisorLogger.detachAppender(appender);
            appLogger.detachAppender(appender);
            advisorLogger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void syncEndpointUsesOpenAiAndReloadsConversationHistory() throws Exception {
        String chatId = newChatId();
        HttpResponse<String> first = get("/ai/love_app/chat/sync", "offline-first-message", chatId, false);

        assertEquals(200, first.statusCode(), first.body());
        assertEquals(SYNC_ANSWER, first.body());
        GatewayRequest firstRequest = takeRequest(false);
        assertTrue(hasMessage(firstRequest.body(), "user", "offline-first-message"));

        // A second HTTP call forces FileBasedChatMemory to read its persisted history.
        HttpResponse<String> second = get("/ai/love_app/chat/sync", "offline-followup-message", chatId, false);

        assertEquals(200, second.statusCode(), second.body());
        assertEquals(SYNC_ANSWER, second.body());
        GatewayRequest secondRequest = takeRequest(false);
        assertTrue(hasMessage(secondRequest.body(), "user", "offline-first-message"),
                "The original user message must survive serialization");
        assertTrue(hasMessage(secondRequest.body(), "assistant", SYNC_ANSWER),
                "The previous assistant answer must be sent to the gateway");
        assertTrue(hasMessage(secondRequest.body(), "user", "offline-followup-message"));
        assertTrue(REQUESTS.isEmpty(), "Plain chat must not invoke extra tools or model calls");
    }

    @Test
    void syncAndSseRequestsUseOnlyTwentyHistoricalMessages() throws Exception {
        for (boolean streaming : new boolean[]{false, true}) {
            String chatId = newChatId();
            List<Message> history = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                history.add(new UserMessage("offline-history-user-" + i));
                history.add(new AssistantMessage("offline-history-answer-" + i));
            }
            chatMemory.add(chatId, history);
            String path = streaming ? "/ai/love_app/chat/sse" : "/ai/love_app/chat/sync";
            HttpResponse<String> response = get(path, "offline-window-question", chatId, streaming);
            assertEquals(200, response.statusCode());
            GatewayRequest request = takeRequest(streaming);
            List<JsonNode> historical = new ArrayList<>();
            request.body().path("messages").forEach(message -> {
                if (message.path("content").asText().startsWith("offline-history-")) {
                    historical.add(message);
                }
            });
            assertEquals(20, historical.size(), "Only the recent history window may reach the model");
            assertEquals("offline-history-user-15", historical.get(0).path("content").asText());
            assertEquals("offline-history-answer-24", historical.get(19).path("content").asText());
            assertTrue(hasMessage(request.body(), "user", "offline-window-question"));
            assertEquals(52, chatMemory.get(chatId, 200).size(), "Context reads must not discard disk history");
            assertTrue(REQUESTS.isEmpty(), "Windowing must not add model calls");
        }
    }

    @Test
    void sseEndpointForwardsOpenAiStreamingText() throws Exception {
        HttpResponse<String> response = get("/ai/love_app/chat/sse", "offline-stream-message", newChatId(), true);

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));
        String eventText = response.body().lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).stripLeading())
                .collect(Collectors.joining());
        assertEquals(STREAM_ANSWER, eventText, response.body());
        GatewayRequest request = takeRequest(true);
        assertTrue(hasMessage(request.body(), "user", "offline-stream-message"));
        assertTrue(REQUESTS.isEmpty(), "Streaming chat should use exactly one gateway request");
    }

    @Test
    void manusEndpointUsesIndependentModelOverrideWithoutChangingLoveModel() throws Exception {
        HttpResponse<String> response = get("/ai/manus/chat", "offline-direct", "unused", true);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("data:" + SYNC_ANSWER), response.body());
        assertTrue(response.body().contains("event:done"), response.body());
        GatewayRequest request = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("fast-test-model", request.body().path("model").asText());
        assertEquals(2048, request.body().path("max_tokens").asInt());
        assertFalse(request.body().path("stream").asBoolean(false));
        assertTrue(REQUESTS.isEmpty());
    }

    @Test
    void manusStreamsLegacyTerminationSummaryUsingNoToolsAndCompletesOnce() throws Exception {
        HttpResponse<String> response = get("/ai/manus/chat", "offline-terminate", "unused", true);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("event:delta"), response.body());
        assertTrue(response.body().contains("{\"delta\":\"offline-\"}"), response.body());
        assertTrue(response.body().contains("{\"delta\":\"stream-answer\"}"), response.body());
        assertEquals(1, response.body().split("event:done", -1).length - 1);
        assertFalse(response.body().contains("event:error"), response.body());
        GatewayRequest planning = REQUESTS.poll(5, TimeUnit.SECONDS);
        GatewayRequest summary = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(planning);
        assertNotNull(summary);
        assertEquals(2048, planning.body().path("max_tokens").asInt());
        assertEquals(2048, summary.body().path("max_tokens").asInt());
        assertFalse(planning.body().path("stream").asBoolean(false));
        assertTrue(summary.body().path("stream").asBoolean(false));
        assertEquals("none", summary.body().path("tool_choice").asText());
        assertEquals(0, summary.body().path("tools").size());
        assertEquals("fast-test-model", summary.body().path("model").asText());
        assertTrue(REQUESTS.isEmpty(), "Only one plan and one necessary summary request");
    }

    @Test
    void manusDefersOpenAiToolCallsUntilAct() throws Exception {
        CountingTool safeTool = new CountingTool();
        // This agent receives only the in-memory tool, never the application's real tools.
        JuziManus agent = new JuziManus(ToolCallbacks.from(safeTool), openAiChatModel);
        agent.getMessageList().add(new UserMessage("offline-tool-message"));

        assertTrue(agent.think(), "An OpenAI tool_calls response must request an act step");
        assertEquals(0, safeTool.calls.get(), "think() must disable automatic tool execution");
        assertNotNull(agent.getToolCallChatResponse());
        assertTrue(agent.getToolCallChatResponse().hasToolCalls());
        assertEquals(TOOL_NAME, agent.getToolCallChatResponse().getResult().getOutput()
                .getToolCalls().get(0).name());
        GatewayRequest request = takeRequest(false);
        assertEquals(1, request.body().path("tools").size());
        assertEquals(TOOL_NAME, request.body().path("tools").get(0).path("function").path("name").asText());

        agent.act();

        assertEquals(1, safeTool.calls.get(), "Only act() should execute the safe tool, exactly once");
        assertTrue(agent.getMessageList().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(result -> TOOL_NAME.equals(result.name()) && result.responseData().contains("safe-count=1")));
        assertTrue(REQUESTS.isEmpty(), "act() must execute locally without another model request");
    }

    @Test
    void fourThousandUnicodeCharactersReachTheApplicationAndOverLimitIsRejectedBeforeGateway() throws Exception {
        HttpResponse<String> accepted = get("/ai/love_app/chat/sync", "😀".repeat(4000), newChatId(), false);
        assertEquals(200, accepted.statusCode(), accepted.body());
        takeRequest(false);
        HttpResponse<String> rejected = get("/ai/love_app/chat/sync", "中".repeat(4001), newChatId(), false);
        assertEquals(400, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("4000"));
        assertTrue(REQUESTS.isEmpty(), "超长输入不能到达模型网关");
    }

    @Test
    void switchingTokenParameterNeverReintroducesTheOriginalDefaultField() throws Exception {
        for (boolean completionDefault : new boolean[]{false, true}) {
            var defaults = OpenAiChatOptions.builder().model("test-model").build();
            if (completionDefault) { defaults.setMaxCompletionTokens(1000); }
            else { defaults.setMaxTokens(1000); }
            OpenAiChatModel source = openAiChatModel.mutate().defaultOptions(defaults).build();
            ModelLimitsProperties limits = new ModelLimitsProperties();
            var selected = completionDefault ? ModelLimitsProperties.TokenParameter.MAX_TOKENS
                    : ModelLimitsProperties.TokenParameter.MAX_COMPLETION_TOKENS;
            limits.setOutputTokenParameter(selected);
            limits.setManusOutputTokenParameter(selected);
            LoveApp app = new LoveApp(source, chatMemory, false, limits);
            assertEquals(SYNC_ANSWER, app.doChat("offline-token-parameter", newChatId()));
            assertSingleOutputTokenField(completionDefault);
            JuziManus agent = new JuziManus(new org.springframework.ai.tool.ToolCallback[0], source, limits);
            agent.getMessageList().add(new UserMessage("offline-token-parameter"));
            assertFalse(agent.think());
            assertSingleOutputTokenField(completionDefault);
            OpenAiChatOptions unchanged = (OpenAiChatOptions) source.getDefaultOptions();
            assertEquals(defaults.getMaxTokens(), unchanged.getMaxTokens());
            assertEquals(defaults.getMaxCompletionTokens(), unchanged.getMaxCompletionTokens());
        }
        assertTrue(REQUESTS.isEmpty());
    }

    private void assertSingleOutputTokenField(boolean originalWasCompletionTokens) throws InterruptedException {
        GatewayRequest request = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(request);
        String selected = originalWasCompletionTokens ? "max_tokens" : "max_completion_tokens";
        String removed = originalWasCompletionTokens ? "max_completion_tokens" : "max_tokens";
        assertEquals(1000, request.body().path(selected).asInt());
        assertFalse(request.body().has(removed), "不能在模型参数合并时重新带回旧字段");
    }

    private String newChatId() {
        String id = "openai-compatibility-" + UUID.randomUUID();
        conversationIds.add(id);
        return id;
    }

    private HttpResponse<String> get(String path, String message, String chatId, boolean stream) throws Exception {
        // All supplied parameter values are URL-safe ASCII generated by this test.
        URI uri = URI.create("http://127.0.0.1:" + port + "/api" + path
                + "?message=" + message + "&chatId=" + chatId);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", stream ? "text/event-stream" : "text/plain")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private GatewayRequest takeRequest(boolean stream) throws InterruptedException {
        GatewayRequest request = REQUESTS.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "The model must call the local OpenAI-compatible gateway");
        assertEquals("POST", request.method());
        assertEquals("/v1/chat/completions", request.path());
        assertEquals("Bearer test-key", request.authorization());
        assertEquals("test-model", request.body().path("model").asText());
        assertEquals(stream, request.body().path("stream").asBoolean(false));
        assertEquals(2048, request.body().path("max_tokens").asInt(), "模型请求必须携带输出上限");
        return request;
    }

    private static boolean hasMessage(JsonNode request, String role, String content) {
        for (JsonNode message : request.path("messages")) {
            if (role.equals(message.path("role").asText()) && content.equals(message.path("content").asText())) {
                return true;
            }
        }
        return false;
    }

    private static HttpServer startGateway() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", OpenAiCompatibilityTest::handleGatewayRequest);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot start loopback OpenAI test server", exception);
        }
    }

    private static void handleGatewayRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            REQUESTS.add(new GatewayRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), request));
            if (!"POST".equals(exchange.getRequestMethod())
                    || !"/v1/chat/completions".equals(exchange.getRequestURI().getPath())
                    || !"Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                send(exchange, 400, "application/json", "{\"error\":\"unexpected offline gateway request\"}");
                return;
            }
            if (request.path("stream").asBoolean(false)) {
                String events = streamChunk(Map.of("role", "assistant", "content", "offline-"), null)
                        + streamChunk(Map.of("content", "stream-answer"), null)
                        + streamChunk(Map.of(), "stop")
                        + "data: [DONE]\n\n";
                send(exchange, 200, "text/event-stream", events);
                return;
            }
            boolean toolCall = request.path("tools").size() > 0 && !hasMessage(request, "user", "offline-direct");
            Map<String, Object> message = toolCall
                    ? Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                            "id", "call_offline_counter", "type", "function",
                            "function", Map.of("name", hasMessage(request, "user", "offline-terminate") ? "doTerminate" : TOOL_NAME, "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", SYNC_ANSWER);
            String body = JSON.writeValueAsString(Map.of(
                    "id", "chatcmpl-offline", "object", "chat.completion", "created", 1,
                    "model", "test-model", "choices", List.of(Map.of("index", 0, "message", message,
                            "finish_reason", toolCall ? "tool_calls" : "stop")),
                    "usage", Map.of("prompt_tokens", 1, "completion_tokens", 1, "total_tokens", 2)));
            send(exchange, 200, "application/json", body);
        }
    }

    private static String streamChunk(Map<String, String> delta, String finishReason) throws IOException {
        // A mutable map permits the null finish_reason used by real OpenAI SSE chunks.
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finishReason);
        return "data: " + JSON.writeValueAsString(Map.of(
                "id", "chatcmpl-offline-stream", "object", "chat.completion.chunk", "created", 1,
                "model", "test-model", "choices", List.of(choice))) + "\n\n";
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + ";charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record GatewayRequest(String method, String path, String authorization, JsonNode body) {
    }

    public static final class CountingTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(description = "Safe offline compatibility test: increment an in-memory counter only")
        public String compatibilityCounter() {
            return "safe-count=" + calls.incrementAndGet();
        }
    }
}
