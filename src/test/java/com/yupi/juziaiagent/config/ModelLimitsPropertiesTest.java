package com.yupi.juziaiagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelLimitsPropertiesTest {
    @Test
    void preservesSmallerConfiguredOutputLimitAndDoesNotMutateSharedOptions() {
        ModelLimitsProperties limits = new ModelLimitsProperties();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        var original = OpenAiChatOptions.builder().model("existing-model").temperature(0.3).maxTokens(1000).build();
        when(model.getDefaultOptions()).thenReturn(original);
        var options = limits.limitedOptions(model);
        assertEquals(1000, options.getMaxTokens());
        assertEquals("existing-model", options.getModel());
        assertEquals(0.3, options.getTemperature());
        assertNotSame(original, options);
        options.setMaxTokens(10);
        assertEquals(1000, original.getMaxTokens());
    }

    @Test
    void capsLargeOutputBudgetAndPreservesCompletionTokenMode() {
        ModelLimitsProperties limits = new ModelLimitsProperties();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxTokens(9000).build());
        assertEquals(2048, limits.limitedOptions(model).getMaxTokens());
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().maxCompletionTokens(9000).build());
        var options = limits.limitedOptions(model);
        assertNull(options.getMaxTokens());
        assertEquals(2048, options.getMaxCompletionTokens());
    }

    @Test
    void setsDefaultOutputBudgetAndRejectsDisabledLimits() {
        ModelLimitsProperties limits = new ModelLimitsProperties();
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().build());
        assertEquals(2048, limits.limitedOptions(model).getMaxTokens());
        limits.afterPropertiesSet();
        limits.setChatTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, limits::afterPropertiesSet);
        limits.setChatTimeout(Duration.ofMinutes(2));
        limits.setMaxConcurrentTasks(0);
        assertThrows(IllegalArgumentException.class, limits::afterPropertiesSet);
    }

    @Test
    void manusCanUseAnotherTokenParameterWithoutChangingLoveAppOrSharedModel() {
        ModelLimitsProperties limits = new ModelLimitsProperties();
        limits.setManusOutputTokenParameter(ModelLimitsProperties.TokenParameter.MAX_COMPLETION_TOKENS);
        OpenAiChatModel model = mock(OpenAiChatModel.class);
        var defaults = OpenAiChatOptions.builder().model("original").maxTokens(1000).build();
        when(model.getDefaultOptions()).thenReturn(defaults);
        var love = limits.limitedOptions(model);
        var manus = limits.limitedManusOptions(model);
        assertEquals(1000, love.getMaxTokens());
        assertNull(love.getMaxCompletionTokens());
        assertNull(manus.getMaxTokens());
        assertEquals(1000, manus.getMaxCompletionTokens());
        assertEquals(1000, defaults.getMaxTokens());
        assertNull(defaults.getMaxCompletionTokens());
    }
}
