package com.mju.linkro.recommendation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * "Now" for recommendation tests: 2026-11-11 09:00 in Seoul (#100). The tests plan appointments on 2026-11-12~15, which
 * must stay in the future whatever day the tests run, since a past appointment is APPOINTMENT_PASSED.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FixedRecommendationClock {
    public static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");

    @Bean
    Clock recommendationClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
