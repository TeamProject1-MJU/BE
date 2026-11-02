package com.mju.linkro.subway.api;

/**
 * A graph node (station on a line) the route passes, in travel order.
 *
 * <p>At a transfer the station appears twice: the node of the line left and the node of the line boarded.
 *
 * @param stationLineId graph node id (station_lines.id); with {@code direction} it is the congestion lookup key
 * @param direction     direction of the train ridden at this stop; null for a stop that belongs to no RIDE
 *                      (for example the only stop of an empty route)
 * @param offsetSec     seconds from departure until the route reaches this stop, including transfer time.
 *                      {@link CongestionLookup} adds it to the departure time to find the 30-minute slot in
 *                      which each stop is passed (API 6.1/8.1), so a long trip can cross slots or days.
 */
public record PathStop(
        long stationId,
        String stationName,
        double latitude,
        double longitude,
        long stationLineId,
        long lineId,
        DirectionCode direction,
        int offsetSec) {

    public PathStop {
        if (offsetSec < 0) {
            throw new IllegalArgumentException("offsetSec must not be negative");
        }
    }
}
