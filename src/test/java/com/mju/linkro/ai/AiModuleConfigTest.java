package com.mju.linkro.ai;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.ai.mock.MockAiConfig;
import com.mju.linkro.ai.openai.OpenAiClient;
import com.mju.linkro.ai.openai.OpenAiConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class AiModuleConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(MockAiConfig.class, OpenAiConfig.class);

    @Test
    void mockIsTheDefault() {
        runner.run(context -> assertThat(context.getBean(AiClient.class)).isInstanceOf(MockAiClient.class));
        runner.withPropertyValues("linkro.ai.module=mock")
                .run(context -> assertThat(context.getBean(AiClient.class)).isInstanceOf(MockAiClient.class));
    }

    @Test
    void openaiWithKeyRegistersOpenAiClient() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy")
                .run(context -> assertThat(context).hasSingleBean(AiClient.class)
                        .doesNotHaveBean(MockAiConfig.class)
                        .getBean(AiClient.class).isInstanceOf(OpenAiClient.class));
    }

    @Test
    void openaiWithBlankKeyFailsStartup() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("apiKey");
                });
        runner.withPropertyValues("linkro.ai.module=openai").run(context -> assertThat(context).hasFailed());
    }
}
