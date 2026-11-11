package com.mju.linkro.ai;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.ai.mock.MockAiConfig;
import com.mju.linkro.ai.openai.OpenAiProperties;
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
    void emptyModuleIsMock() {
        runner.withPropertyValues("linkro.ai.module=")
                .run(context -> assertThat(context.getBean(AiClient.class)).isInstanceOf(MockAiClient.class));
    }

    @Test
    void openaiWithKeyRegistersOpenAiClient() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy",
                        "linkro.ai.openai.model=test-model")
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

    @Test
    void openaiWithBlankModelFailsStartup() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy",
                        "linkro.ai.openai.model=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("model");
                });
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("model");
                });
    }

    /**
     * docker-compose passes OPENAI_*_TIMEOUT as ${VAR:-}: an unset server value arrives as an empty environment
     * variable, which wins over the application.yaml fallback (${OPENAI_READ_TIMEOUT:8s} -> ""). Empty must mean the
     * default, not a failed startup.
     */
    @Test
    void emptyTimeoutVariablesKeepTheDefaults() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy", "linkro.ai.openai.model=test-model",
                        "OPENAI_CONNECT_TIMEOUT=", "OPENAI_READ_TIMEOUT=", "OPENAI_VISION_READ_TIMEOUT=", "OPENAI_EXPLAIN_READ_TIMEOUT=",
                        "linkro.ai.openai.connect-timeout=${OPENAI_CONNECT_TIMEOUT:2s}",
                        "linkro.ai.openai.read-timeout=${OPENAI_READ_TIMEOUT:8s}",
                        "linkro.ai.openai.vision-read-timeout=${OPENAI_VISION_READ_TIMEOUT:4s}",
                        "linkro.ai.openai.explain-read-timeout=${OPENAI_EXPLAIN_READ_TIMEOUT:6s}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OpenAiProperties props = context.getBean(OpenAiProperties.class);
                    assertThat(props.getConnectTimeout()).isEqualTo(java.time.Duration.ofSeconds(2));
                    assertThat(props.getReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(8));
                    assertThat(props.getVisionReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(4));
                    assertThat(props.getExplainReadTimeout()).isEqualTo(java.time.Duration.ofSeconds(6));
                });
    }

    @Test
    void setTimeoutVariablesAreUsed() {
        runner.withPropertyValues("linkro.ai.module=openai", "linkro.ai.openai.api-key=test-dummy", "linkro.ai.openai.model=test-model",
                        "OPENAI_EXPLAIN_READ_TIMEOUT=9s", "linkro.ai.openai.explain-read-timeout=${OPENAI_EXPLAIN_READ_TIMEOUT:6s}")
                .run(context -> assertThat(context.getBean(OpenAiProperties.class).getExplainReadTimeout())
                        .isEqualTo(java.time.Duration.ofSeconds(9)));
    }
}
