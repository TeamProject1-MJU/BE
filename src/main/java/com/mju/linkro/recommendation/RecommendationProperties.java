package com.mju.linkro.recommendation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tie tolerances of API 8.1 ("허용 폭은 설정값"). The FARE criterion treats candidates within
 * {@code fareToleranceWon} of the cheapest as tied and the DISTANCE criterion within {@code distanceToleranceM} of the
 * shortest; ties are decided by the TIME rule.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.recommendation")
public class RecommendationProperties {
    private int fareToleranceWon = 100;
    private int distanceToleranceM = 1_000;

    public RecommendationProperties() {}

    public RecommendationProperties(int fareToleranceWon, int distanceToleranceM) {
        this.fareToleranceWon = fareToleranceWon;
        this.distanceToleranceM = distanceToleranceM;
    }
}
