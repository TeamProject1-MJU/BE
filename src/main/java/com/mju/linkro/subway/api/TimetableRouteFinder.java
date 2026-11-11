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
