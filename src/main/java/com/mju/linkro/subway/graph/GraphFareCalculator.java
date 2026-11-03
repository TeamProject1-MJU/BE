package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.RoutePath;

/** 8.1 fare from RIDE distance: base fare, then one step per started mid/long distance unit. */
public class GraphFareCalculator implements FareCalculator {
    private final SubwayFareProperties fare;

    public GraphFareCalculator(SubwayFareProperties fare) {
        fare.validate();
        this.fare = fare;
    }

    @Override
    public int fareOf(RoutePath path) {
        int distanceM = path.rideDistanceM();
        if (distanceM < 0) {
            throw new IllegalArgumentException("distance must not be negative");
        }
        int middle = Math.max(0, Math.min(distanceM, fare.getMidLimitM()) - fare.getBaseDistanceM());
        int far = Math.max(0, distanceM - fare.getMidLimitM());
        return fare.getBaseFare() + (steps(middle, fare.getMidStepM()) + steps(far, fare.getLongStepM())) * fare.getStepFare();
    }

    private static int steps(int distanceM, int stepM) {
        return (distanceM + stepM - 1) / stepM;
    }
}
