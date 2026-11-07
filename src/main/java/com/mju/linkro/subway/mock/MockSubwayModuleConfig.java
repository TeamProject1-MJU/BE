package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Mock routes and fares unless {@code linkro.subway.module} names another implementation (graph); empty means mock. */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Conditional(MockSwitchCondition.Module.class)
public class MockSubwayModuleConfig {

    public MockSubwayModuleConfig() {
        log.warn("Subway route module is MOCK: routes and fares are fake. Set LINKRO_SUBWAY_MODULE=graph in production.");
    }

    @Bean
    SubwayRouteFinder subwayRouteFinder() {
        return new MockSubwayRouteFinder();
    }

    @Bean
    FareCalculator fareCalculator() {
        return new MockFareCalculator();
    }
}
