package com.mju.linkro.ai.mock;

import com.mju.linkro.ai.api.AiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mock AI unless {@code linkro.ai.module} names another implementation (openai). */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.ai.module", havingValue = "mock", matchIfMissing = true)
public class MockAiConfig {

    public MockAiConfig() {
        log.warn("AI is MOCK: answers are fixed rules. Set LINKRO_AI_MODULE=openai in production.");
    }

    @Bean
    AiClient aiClient() {
        return new MockAiClient();
    }
}
