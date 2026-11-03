package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionLookup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Mock congestion until the real lookup exists; then {@code linkro.subway.congestion} switches it off. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.congestion", havingValue = "mock", matchIfMissing = true)
public class MockCongestionConfig {

    public MockCongestionConfig() {
        log.warn("Subway congestion is MOCK: congestion values are fake.");
    }

    @Bean
    CongestionLookup congestionLookup() {
        return new MockCongestionLookup();
    }
}
