package com.mju.linkro.ai.openai;

import com.mju.linkro.ai.api.AiClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Real OpenAI client when {@code linkro.ai.module=openai}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.ai.module", havingValue = "openai")
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfig {

    @Bean
    AiClient aiClient(OpenAiProperties props) {
        return new OpenAiClient(restClient(props, props.getReadTimeout()),
                restClient(props, props.getVisionReadTimeout()), props.getApiKey(), props.getModel());
    }

    // Separate clients because text and vision calls have different read timeouts (Kakao client pattern).
    private static RestClient restClient(OpenAiProperties props, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeout());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(OpenAiClient.BASE_URL).requestFactory(factory).build();
    }
}
