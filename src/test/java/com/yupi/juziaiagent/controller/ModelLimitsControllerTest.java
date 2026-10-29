package com.yupi.juziaiagent.controller;

import com.yupi.juziaiagent.app.LoveApp;
import com.yupi.juziaiagent.config.ModelLimitsProperties;
import com.yupi.juziaiagent.limits.ModelCallGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 假 LoveApp + MVC，不启动模型、MCP、数据库或外网连接。 */
class ModelLimitsControllerTest {
    private final ModelLimitsProperties limits = new ModelLimitsProperties();
    private final LoveApp app = mock(LoveApp.class);
    private ModelCallGuard guard;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        limits.setMaxInputChars(4);
        limits.setMaxConcurrentTasks(1);
        limits.setMaxTasksPerMinute(2);
        limits.setChatTimeout(Duration.ofMillis(500));
        guard = new ModelCallGuard(limits);
        AiController controller = new AiController();
        ReflectionTestUtils.setField(controller, "loveApp", app);
        ReflectionTestUtils.setField(controller, "modelCallGuard", guard);
        ReflectionTestUtils.setField(controller, "modelLimits", limits);
        mvc = standaloneSetup(controller).setControllerAdvice(new ModelLimitExceptionHandler())
                .setAsyncRequestTimeout(3000).build();
    }

    @AfterEach
    void stopWorkers() {
        guard.destroy();
    }

    @Test
    void allSixEndpointsRejectInvalidInputsBeforeStartingAnyModel() throws Exception {
        for (String path : new String[]{"/love_app/chat/sync", "/love_app/chat/mcp", "/love_app/chat/sse",
                "/love_app/chat/sse/event", "/love_app/chat/sse/emitter", "/manus/chat"}) {
            for (String text : new String[]{"", "     ", "12345"}) {
                mvc.perform(get("/ai" + path).param("message", text).param("chatId", "offline"))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN));
            }
        }
        verifyNoInteractions(app);
    }

    @Test
    void rateLimitRejectsBeforeModelAndReturnsRetryAfter() throws Exception {
        when(app.doChat("ok", "offline")).thenReturn("answer");
        for (int i = 0; i < 2; i++) {
            mvc.perform(get("/ai/love_app/chat/sync").param("message", "ok").param("chatId", "offline"))
                    .andExpect(status().isOk()).andExpect(content().string("answer"));
        }
        mvc.perform(get("/ai/love_app/chat/sync").param("message", "ok").param("chatId", "offline"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        verify(app, times(2)).doChat("ok", "offline");
    }

    @Test
    void busyLimitCoversEveryEndpointIncludingManusAndManualEmitter() throws Exception {
        try (var lease = guard.acquire("held")) {
            for (String path : new String[]{"/love_app/chat/sync", "/love_app/chat/mcp", "/love_app/chat/sse",
                    "/love_app/chat/sse/event", "/love_app/chat/sse/emitter", "/manus/chat"}) {
                MvcResult response = mvc.perform(get("/ai" + path).param("message", "ok")
                        .param("chatId", "offline")).andReturn();
                if (response.getRequest().isAsyncStarted()) {
                    response.getAsyncResult(2000);
                    mvc.perform(asyncDispatch(response)).andExpect(status().isTooManyRequests())
                            .andExpect(header().exists("Retry-After"));
                } else {
                    assertEquals(429, response.getResponse().getStatus(), path);
                    assertNotNull(response.getResponse().getHeader("Retry-After"), path);
                }
            }
        }
        verifyNoInteractions(app);
    }

    @Test
    void silentStreamTimesOutAndFreesSlot() throws Exception {
        when(app.doChatByStream("ok", "offline")).thenReturn(Flux.never());
        MvcResult response = mvc.perform(get("/ai/love_app/chat/sse").param("message", "ok")
                .param("chatId", "offline")).andExpect(request().asyncStarted()).andReturn();
        response.getAsyncResult(2000);
        mvc.perform(asyncDispatch(response)).andExpect(status().isGatewayTimeout());
        guard.acquire("next").close();
    }

    @Test
    void partiallySentStreamsKeepAnswerAndSendAnExplicitTimeoutError() throws Exception {
        for (String path : new String[]{"/love_app/chat/sse", "/love_app/chat/sse/event", "/love_app/chat/sse/emitter"}) {
            // 本测试依次覆盖三个入口，使用较高频率额度。
            limits.setMaxTasksPerMinute(20);
            when(app.doChatByStream("ok", "offline")).thenReturn(Flux.concat(Flux.just("partial"), Flux.never()));
            MvcResult response = mvc.perform(get("/ai" + path).param("message", "ok")
                    .param("chatId", "offline")).andExpect(request().asyncStarted()).andReturn();
            response.getAsyncResult(2000);
            String body = mvc.perform(asyncDispatch(response)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(body.contains("data:partial"), body);
            assertTrue(body.contains("event:error"), body);
            assertTrue(body.contains("超过时间上限"), path + ": " + body);
            assertEquals(1, body.split("event:error", -1).length - 1, body);
        }
    }
}
