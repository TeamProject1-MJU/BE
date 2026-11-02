package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the mock subway module unless {@code linkro.subway.module} names another implementation (e.g. graph). */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "mock", matchIfMissing = true)
public class MockSubwayModuleConfig {

    public MockSubwayModuleConfig() {
        log.warn("Subway module is MOCK: routes, fares and congestion are fake. Set linkro.subway.module=graph in production.");
    }

    @Bean
    SubwayRouteFinder subwayRouteFinder() {
        return new MockSubwayRouteFinder();
    }

    @Bean
    FareCalculator fareCalculator() {
        return new MockFareCalculator();
    }

    @Bean
    CongestionLookup congestionLookup() {
        return new MockCongestionLookup();
    }
}
