package com.mju.linkro.ai.openai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code linkro.ai.openai.*}. Bound only with {@code linkro.ai.module=openai}; a blank key fails startup. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "linkro.ai.openai")
public class OpenAiProperties {
    @NotBlank
    private String apiKey;
    /** One model for text and images (API spec 9.1: an image-capable model, name from settings). */
    @NotBlank
    private String model = "GPT-6.1 Terra";
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(8);
    @NotNull
    private Duration visionReadTimeout = Duration.ofSeconds(4);

    @Override
    public String toString() {
        return "OpenAiProperties[model=" + model + "]";
    }
}
