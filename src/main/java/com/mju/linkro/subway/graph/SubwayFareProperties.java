package com.mju.linkro.subway.graph;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Adult transit-card fare settings ({@code linkro.subway.fare.*}); defaults in subway-graph-defaults.properties. */
@Getter
@Setter
@ConfigurationProperties(prefix = "linkro.subway.fare")
public class SubwayFareProperties {
    private int baseFare;
    private int baseDistanceM;
    private int midLimitM;
    private int midStepM;
    private int longStepM;
    private int stepFare;

    void validate() {
        requirePositive("base-fare", baseFare);
        requirePositive("base-distance-m", baseDistanceM);
        requirePositive("mid-limit-m", midLimitM);
        requirePositive("mid-step-m", midStepM);
        requirePositive("long-step-m", longStepM);
        requirePositive("step-fare", stepFare);
        if (midLimitM < baseDistanceM) {
            throw new IllegalArgumentException("linkro.subway.fare.mid-limit-m must not be below base-distance-m");
        }
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("linkro.subway.fare." + name + " must be positive: " + value);
        }
    }
}
