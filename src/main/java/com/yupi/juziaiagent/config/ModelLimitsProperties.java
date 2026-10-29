package com.yupi.juziaiagent.config;

import lombok.Data;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.Duration;

/** 本机聊天任务的基础上限；配置错误时拒绝启动，避免悄悄失去保护。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.model-limits")
public class ModelLimitsProperties implements InitializingBean {
    private int maxInputChars = 4000;
    private int maxOutputTokens = 2048;
    private int maxConcurrentTasks = 2;
    private int maxTasksPerMinute = 20;
    private Duration chatTimeout = Duration.ofMinutes(2);
    private Duration manusTimeout = Duration.ofMinutes(5);
    private int manusMaxSteps = 20;
    private TokenParameter outputTokenParameter = TokenParameter.AUTO;
    private TokenParameter manusOutputTokenParameter = TokenParameter.AUTO;

    public enum TokenParameter { AUTO, MAX_TOKENS, MAX_COMPLETION_TOKENS }

    @Override
    public void afterPropertiesSet() {
        Assert.isTrue(maxInputChars > 0, "max-input-chars 必须大于 0");
        Assert.isTrue(maxOutputTokens > 0, "max-output-tokens 必须大于 0");
        Assert.isTrue(maxConcurrentTasks > 0, "max-concurrent-tasks 必须大于 0");
        Assert.isTrue(maxTasksPerMinute > 0, "max-tasks-per-minute 必须大于 0");
        Assert.isTrue(chatTimeout != null && chatTimeout.toMillis() > 0, "chat-timeout 必须至少为 1ms");
        Assert.isTrue(manusTimeout != null && manusTimeout.toMillis() > 0, "manus-timeout 必须至少为 1ms");
        Assert.isTrue(manusMaxSteps > 0, "manus-max-steps 必须大于 0");
        Assert.notNull(outputTokenParameter, "output-token-parameter 不能为空");
        Assert.notNull(manusOutputTokenParameter, "manus-output-token-parameter 不能为空");
    }

    /** 保留现有模型参数，较小的输出上限继续生效；兼容两种 token 字段。 */
    public OpenAiChatOptions limitedOptions(OpenAiChatModel model) {
        return limitedOptions(model, outputTokenParameter);
    }

    public OpenAiChatOptions limitedManusOptions(OpenAiChatModel model) {
        return limitedOptions(model, manusOutputTokenParameter == TokenParameter.AUTO
                ? outputTokenParameter : manusOutputTokenParameter);
    }

    private OpenAiChatOptions limitedOptions(OpenAiChatModel model, TokenParameter parameter) {
        OpenAiChatOptions options = model.getDefaultOptions() instanceof OpenAiChatOptions defaults
                ? defaults.copy() : OpenAiChatOptions.builder().build();
        int budget = bounded(options.getMaxCompletionTokens() != null
                ? options.getMaxCompletionTokens() : options.getMaxTokens());
        boolean completionTokens = parameter == TokenParameter.MAX_COMPLETION_TOKENS
                || (parameter == TokenParameter.AUTO && options.getMaxCompletionTokens() != null);
        if (completionTokens) {
            options.setMaxCompletionTokens(budget);
            options.setMaxTokens(null);
        } else {
            options.setMaxTokens(budget);
            options.setMaxCompletionTokens(null);
        }
        return options;
    }

    private int bounded(Integer configured) {
        return configured == null || configured <= 0 ? maxOutputTokens : Math.min(configured, maxOutputTokens);
    }
}
