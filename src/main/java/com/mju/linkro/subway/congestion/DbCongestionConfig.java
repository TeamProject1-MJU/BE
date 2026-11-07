package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Real congestion from congestion_stats when {@code linkro.subway.congestion=db} (environment variable
 * LINKRO_SUBWAY_CONGESTION=db). Turn it on only after the production DB is loaded; the default stays mock.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "linkro.subway.congestion", havingValue = "db")
public class DbCongestionConfig {

    public DbCongestionConfig() {
        log.info("Subway congestion is DB: congestion_stats, source priority PREDICTED > ESTIMATED > MOCK.");
    }

    @Bean
    CongestionLookup congestionLookup(CongestionStatsRepository stats, CongestionResolver resolver) {
        return new DbCongestionLookup(stats, resolver);
    }
}
