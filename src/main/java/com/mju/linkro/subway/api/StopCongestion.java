package com.mju.linkro.subway.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Congestion of one stop of a route, in the order of {@link RoutePath#stops()}.
 *
 * @param direction  direction of the train at this stop; null for a stop on no RIDE (always gray)
 * @param slotDate   service day of the row looked up (Asia/Seoul). For a pass at 00:00~00:59 it is the previous
 *                   calendar date; with {@code firstTrain} after the last train it is the next day
 * @param slotStart  30-minute slot looked up (05:30~23:30, 00:00, 00:30)
 * @param firstTrain true when the pass time has no service (01:00~05:29) or is after the last train, and the
 *                   first-train 05:30 slot was used instead
 * @param congestion the stop's value; {@link CongestionResult#stops()} is always empty here. Unsupported (gray)
 *                   when there is no row; then the slot fields still describe the slot of the pass time
 */
public record StopCongestion(
        long stationId,
        long stationLineId,
        long lineId,
        DirectionCode direction,
        LocalDate slotDate,
        LocalTime slotStart,
        boolean firstTrain,
        CongestionResult congestion) {

    public StopCongestion {
        Objects.requireNonNull(slotDate, "slotDate");
        Objects.requireNonNull(slotStart, "slotStart");
        Objects.requireNonNull(congestion, "congestion");
        if (!congestion.stops().isEmpty()) {
            throw new IllegalArgumentException("a stop's congestion has no stops of its own");
        }
    }
}
