package com.mju.linkro.subway.api;

import java.util.Objects;

/**
 * One segment of a route: a ride on one line in one direction, or a transfer walk inside a station.
 *
 * @param lineId        line of a RIDE; for a TRANSFER, the line being transferred to
 * @param direction     train direction of a RIDE; always null for a TRANSFER
 * @param stationCount  stations moved on a RIDE (boarding station excluded); 0 for a TRANSFER
 * @param durationSec   ride time, or transfer walking time
 * @param distanceM     ride distance, or transfer walking distance (not used for fares)
 */
public record PathStep(
        StepType type,
        Long lineId,
        String lineName,
        String color,
        DirectionCode direction,
        long fromStationId,
        String fromStationName,
        long toStationId,
        String toStationName,
        int stationCount,
        int durationSec,
        int distanceM) {

    public PathStep {
        Objects.requireNonNull(type, "type");
        if (type == StepType.RIDE && direction == null) {
            throw new IllegalArgumentException("RIDE needs a direction");
        }
        if (type == StepType.TRANSFER && direction != null) {
            throw new IllegalArgumentException("TRANSFER has no direction");
        }
    }
}
