package com.yupi.juziaiagent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ToolRegistration.class)
            .withPropertyValues("search-api.api-key=test-key");

    @Test
    void terminalToolIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(toolNames(context.getBean(ToolCallback[].class)))
                    .isNotEmpty()
                    .doesNotContain("executeTerminalCommand")
                    .contains("doTerminate");
        });
    }

    @Test
    void terminalToolIsDisabledWhenConfiguredFalse() {
        contextRunner.withPropertyValues("app.tools.terminal.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(toolNames(context.getBean(ToolCallback[].class)))
                    .doesNotContain("executeTerminalCommand");
        });
    }

    @Test
    void explicitOptInAddsOnlyTheTerminalTool() {
        contextRunner.run(defaultContext ->
                contextRunner.withPropertyValues("app.tools.terminal.enabled=true").run(enabledContext -> {
                    assertThat(defaultContext).hasNotFailed();
                    assertThat(enabledContext).hasNotFailed();
                    Set<String> defaultNames = toolNames(defaultContext.getBean(ToolCallback[].class));
                    Set<String> enabledNames = toolNames(enabledContext.getBean(ToolCallback[].class));
                    assertThat(enabledNames).containsAll(defaultNames)
                            .contains("executeTerminalCommand")
                            .hasSize(defaultNames.size() + 1);
                }));
    }

    private static Set<String> toolNames(ToolCallback[] callbacks) {
        return Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
