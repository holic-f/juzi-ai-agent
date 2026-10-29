package com.yupi.juziaiagent.controller;

import com.yupi.juziaiagent.agent.model.JuziManus;
import com.yupi.juziaiagent.app.LoveApp;
import com.yupi.juziaiagent.config.ModelLimitsProperties;
import com.yupi.juziaiagent.limits.ModelCallGuard;
import com.yupi.juziaiagent.limits.ModelLimitException;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
// import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.core.Disposables;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private LoveApp loveApp;

    @Resource
    private ModelCallGuard modelCallGuard;

    @Resource
    private ModelLimitsProperties modelLimits;

    @Resource
    private ToolCallback[] allTools;

    // 原写法：@Resource
    // private ChatModel dashscopeChatModel;
    @Resource
    private OpenAiChatModel openAiChatModel;

    // 空值沿用 OpenAI 全局模型；只对 Manus 生效，不影响恋爱助手。
    @Value("${app.agent.model:}")
    private String manusModel;

    @Value("${app.agent.max-tool-result-chars:6000}")
    private int maxToolResultChars;

    @GetMapping("/love_app/chat/sync")
    public String doChatWithLoveAppSync(String message, String chatId) {
        // 原写法：return loveApp.doChatWithMcp(message, chatId);
        // 基础对话不依赖 MCP；MCP 功能仍保留在 LoveApp.doChatWithMcp 中。
        return modelCallGuard.call(message, () -> loveApp.doChat(message, chatId));
    }

    // MCP 工具对话单独保留，不让基础聊天依赖外部 MCP 服务。
    @GetMapping("/love_app/chat/mcp")
    public String doChatWithLoveAppMcp(String message, String chatId) {
        return modelCallGuard.call(message, () -> loveApp.doChatWithMcp(message, chatId));
    }

    @GetMapping(value = "/love_app/chat/sse", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<?>> doChatWithLoveAppSSE(String message, String chatId) {
        return loveEvents(message, chatId);
    }

    // 原写法：@GetMapping(value = "/love_app/chat/sse")（与上方 SSE 接口路径重复）
    @GetMapping(value = "/love_app/chat/sse/event", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<?>> doChatWithLoveAppServerSentEvent(String message, String chatId) {
        return loveEvents(message, chatId);
    }

    private Flux<ServerSentEvent<?>> loveEvents(String message, String chatId) {
        modelCallGuard.validate(message);
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean();
            return modelCallGuard.stream(message, () -> loveApp.doChatByStream(message, chatId))
                    .<ServerSentEvent<?>>map(chunk -> {
                        emitted.set(true);
                        return ServerSentEvent.builder(chunk).build();
                    })
                    .onErrorResume(ModelLimitException.class, error -> emitted.get()
                            ? Flux.just(ServerSentEvent.builder(Map.of("message", error.getMessage())).event("error").build())
                            : Flux.error(error));
        });
    }

    @GetMapping(value = "/love_app/chat/sse/emitter", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter doChatWithLoveAppSseEmitter(String message, String chatId) {
        modelCallGuard.validate(message);
        SseEmitter emitter = new SseEmitter(modelLimits.getChatTimeout().toMillis() + 1000);
        Disposable.Swap subscription = Disposables.swap();
        AtomicBoolean emitted = new AtomicBoolean();
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            subscription.dispose();
            emitter.completeWithError(new ModelLimitException(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                    "本次聊天已超过时间上限，请缩短问题后重试。"));
        });
        emitter.onError(error -> subscription.dispose());
        subscription.update(modelCallGuard.stream(message, () -> loveApp.doChatByStream(message, chatId))
                .subscribe(
                        // 处理每条消息
                        chunk -> {
                            try {
                                emitter.send(chunk);
                                emitted.set(true);
                            } catch (IOException | IllegalStateException e) {
                                subscription.dispose();
                                emitter.completeWithError(e);
                            }
                        },
                        // 处理错误
                        error -> {
                            if (emitted.get() && error instanceof ModelLimitException) {
                                try {
                                    emitter.send(SseEmitter.event().name("error")
                                            .data(Map.of("message", error.getMessage()), MediaType.APPLICATION_JSON));
                                    emitter.complete();
                                } catch (IOException | IllegalStateException sendError) {
                                    emitter.completeWithError(sendError);
                                }
                            } else {
                                emitter.completeWithError(error);
                            }
                        },
                        // 处理完成
                        emitter::complete
                ));
        // 返回emitter
        return emitter;
    }


    /**
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping(value = "/manus/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter doChatWithManus(String message) {
        // 原写法：JuziManus yuManus = new JuziManus(allTools, dashscopeChatModel);
        return modelCallGuard.startAgent(message, () -> {
            JuziManus yuManus = new JuziManus(allTools, openAiChatModel, modelLimits);
            if (manusModel != null && !manusModel.isBlank()) {
                yuManus.getChatOptions().setModel(manusModel.trim());
            }
            yuManus.setMaxToolResultChars(maxToolResultChars);
            return yuManus;
        });
    }




}

