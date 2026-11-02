package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.RoutePath;

/** Uses the real 8.1 fare formula with fixed values; the real module may read them from configuration. */
public class MockFareCalculator implements FareCalculator {
    static final int BASE_FARE = 1_550;
    static final int BASE_DISTANCE_M = 10_000;
    static final int MIDDLE_LIMIT_M = 50_000;
    static final int MIDDLE_STEP_M = 5_000;
    static final int FAR_STEP_M = 8_000;
    static final int STEP_FARE = 100;

    @Override
    public int fareOf(RoutePath path) {
        return fareOfDistance(path.rideDistanceM());
    }

    static int fareOfDistance(int distanceM) {
        if (distanceM < 0) {
            throw new IllegalArgumentException("distance must not be negative");
        }
        int middle = Math.max(0, Math.min(distanceM, MIDDLE_LIMIT_M) - BASE_DISTANCE_M);
        int far = Math.max(0, distanceM - MIDDLE_LIMIT_M);
        return BASE_FARE + steps(middle, MIDDLE_STEP_M) * STEP_FARE + steps(far, FAR_STEP_M) * STEP_FARE;
    }

    /** Started steps count in full: 1 m over a boundary adds one step. */
    private static int steps(int distanceM, int stepM) {
        return (distanceM + stepM - 1) / stepM;
    }
}
