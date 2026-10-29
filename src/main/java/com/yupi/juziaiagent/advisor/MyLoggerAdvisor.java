package com.yupi.juziaiagent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认仅记录耗时、长度和完成状态。正文需显式开启配置并启用 DEBUG 日志。
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    private final boolean logContent;

    public MyLoggerAdvisor() {
        this(false);
    }

    public MyLoggerAdvisor(boolean logContent) {
        this.logContent = logContent;
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private String requestText(ChatClientRequest request) {
        var message = request.prompt().getUserMessage();
        return message == null || message.getText() == null ? "" : message.getText();
    }

    private String responseText(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return "";
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private void logBody(String direction, String text) {
        if (logContent && log.isDebugEnabled()) {
            log.debug("AI {} body: {}", direction, text);
        }
    }

    private void logMetrics(String mode, String status, long started, int inputChars, long outputChars) {
        log.info("AI chat mode={} status={} durationMs={} inputChars={} outputChars={}",
                mode, status, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                inputChars, outputChars);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        long started = System.nanoTime();
        String input = requestText(chatClientRequest);
        logBody("request", input);
        String status = "error";
        int outputChars = 0;
        try {
            ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
            String output = responseText(response);
            outputChars = output.length();
            logBody("response", output);
            status = "complete";
            return response;
        } finally {
            // 不输出异常消息或堆栈，其中也可能包含聊天正文。
            logMetrics("call", status, started, input.length(), outputChars);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return Flux.defer(() -> {
            long started = System.nanoTime();
            String input = requestText(chatClientRequest);
            AtomicLong outputChars = new AtomicLong();
            logBody("request", input);
            // 只观察并透传分片，不为日志聚合完整答案；每次订阅独立计数。
            return Flux.defer(() -> streamAdvisorChain.nextStream(chatClientRequest))
                    .doOnNext(response -> {
                        String output = responseText(response);
                        outputChars.addAndGet(output.length());
                        logBody("response chunk", output);
                    })
                    .doFinally(signal -> logMetrics("stream", switch (signal) {
                        case ON_COMPLETE -> "complete";
                        case CANCEL -> "cancel";
                        default -> "error";
                    }, started, input.length(), outputChars.get()));
        });
    }
}
