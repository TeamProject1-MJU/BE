package com.mju.linkro.subway.api;

/** Estimated fare for a route on our graph (8.1, and 6.1 when the Kakao call fails). Not stored. */
public interface FareCalculator {

    /**
     * Adult transit-card fare in won from {@link RoutePath#rideDistanceM()} (RIDE distance only, transfers
     * excluded, all lines summed as one trip): 1,550 up to 10 km, +100 per started 5 km above 10 km, and +100 per
     * started 8 km above 50 km. For example 10.1 km is 1,650 and 15.1 km is 1,750.
     */
    int fareOf(RoutePath path);
}
