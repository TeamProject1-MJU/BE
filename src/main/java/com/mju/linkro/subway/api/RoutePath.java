package com.mju.linkro.subway.api;

import java.util.List;

/**
 * Minimum-time route between two stations on our graph (an estimate, not a Kakao route).
 *
 * <p>When the origin and destination are the same station the route is empty: no steps, one stop and all
 * totals 0.
 *
 * @param stops         every node passed, from the origin to the destination
 * @param totalSeconds  sum of all step durations, transfer time included
 * @param transferCount number of TRANSFER steps
 * @param rideDistanceM sum of RIDE distances only; transfer walking is excluded, so this is the fare distance
 */
public record RoutePath(
        List<PathStep> steps,
        List<PathStop> stops,
        int totalSeconds,
        int transferCount,
        int rideDistanceM,
        long fromStationId,
        long toStationId) {

    public RoutePath {
        steps = List.copyOf(steps);
        stops = List.copyOf(stops);
        if (stops.isEmpty()) {
            throw new IllegalArgumentException("a route has at least one stop");
        }
    }
}
