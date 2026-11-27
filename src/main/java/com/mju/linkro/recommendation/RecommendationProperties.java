package com.mju.linkro.recommendation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tie tolerance of API 8.1 ("허용 폭은 설정값"). The FARE criterion treats candidates within {@code fareToleranceWon} of
 * the cheapest as tied; ties are decided by the TIME rule. DISTANCE has no tolerance since #193 (PR #194 review): it
 * orders every candidate by farthest rider, spread, total and station id.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.recommendation")
public class RecommendationProperties {
    private int fareToleranceWon = 100;
    /** Longest a recommendation calculation holds its room (#98) before the next request may take over. */
    private int inProgressMaxSeconds = 60;

    public RecommendationProperties() {}

    public RecommendationProperties(int fareToleranceWon) {
        this.fareToleranceWon = fareToleranceWon;
    }
}
