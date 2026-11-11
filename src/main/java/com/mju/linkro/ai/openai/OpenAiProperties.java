package com.mju.linkro.ai.openai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code linkro.ai.openai.*}. Bound only with {@code linkro.ai.module=openai}; a blank key or model fails startup.
 *
 * <p>An empty timeout (docker-compose passes OPENAI_*_TIMEOUT as {@code ${VAR:-}}, so an unset server value is an empty
 * variable) is not bound and leaves the field default below, so these defaults must stay equal to application.yaml's.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "linkro.ai.openai")
public class OpenAiProperties {
    @NotBlank
    private String apiKey;
    /** One model for text and images (API spec 9.1). No default id; blank fails startup, same as a blank key. */
    @NotBlank
    private String model;
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(8);
    @NotNull
    private Duration visionReadTimeout = Duration.ofSeconds(4);
    /** Sign reads only (OPENAI_VISION_REASONING_EFFORT, e.g. none/low). Blank sends no reasoning setting. */
    private String visionReasoningEffort;
    /** explainRecommendation only (#99): past it the recommendation uses template sentences. */
    @NotNull
    private Duration explainReadTimeout = Duration.ofSeconds(6);

    @Override
    public String toString() {
        return "OpenAiProperties[model=" + model + "]";
    }
}
