package com.mju.linkro.subway.api;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes on real trains of the Seoul Metro timetable (#96). Waits are the time until the next train, transfers are
 * walking time plus that wait, and staying on one train costs nothing. Exists only with
 * {@code linkro.subway.module=graph} and {@code linkro.subway.timetable.enabled=true}.
 *
 * <p>Station ids are validated like {@link SubwayRouteFinder}: STATION_NOT_FOUND / STATION_UNSUPPORTED.
 */
public interface TimetableRouteFinder {

    /** False only when the timetable has no train at all for the service day of {@code at}. */
    boolean covers(Instant at);

    /**
     * True when no train of any line arrives anywhere in the configured window before {@code at}
     * ({@code linkro.subway.timetable.no-service-window-minutes}, default 60): the subway is not running then. Trains
     * arrive from the day's first arrival to its last one; the service day of {@code at} and the one before are both
     * checked, since a night can belong to either.
     */
    boolean noTrainArrivesBefore(Instant at);

    /** Supported stations of the graph. */
    Set<Long> stationIds();

    /** @throws com.mju.linkro.common.exception.BusinessException STATION_NOT_FOUND / STATION_UNSUPPORTED */
    void requireStation(long stationId);

    /**
     * Latest-departure routes to {@code destinationStationId} arriving by {@code arriveBy}, for each origin.
     * An origin missing from the map cannot arrive in time on that service day's trains. Origins that cannot leave
     * after {@code arriveBy − maxTravelSeconds} are also left out (0 means no limit).
     */
    Map<Long, TimedRoute> latestDepartures(long destinationStationId, Instant arriveBy, Set<Long> originStationIds,
                                           int maxTravelSeconds);

    /** Earliest-arrival routes leaving {@code originStationId} at {@code leaveAt}. */
    TimedTree departingAt(long originStationId, Instant leaveAt);

    /** Result of {@link #departingAt}. */
    interface TimedTree {
        Set<Long> reachableStationIds();

        Optional<TimedRoute> routeTo(long stationId);
    }
}
