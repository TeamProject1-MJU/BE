package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionLookup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Mock congestion unless {@code linkro.subway.congestion} names another implementation (db); empty means mock. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Conditional(MockSwitchCondition.Congestion.class)
public class MockCongestionConfig {

    public MockCongestionConfig() {
        log.warn("Subway congestion is MOCK: congestion values are fake.");
    }

    @Bean
    CongestionLookup congestionLookup() {
        return new MockCongestionLookup();
    }
}
