package com.mju.linkro.subway.api;

/**
 * Minimum-time routes on our subway graph (station_lines + station_connections, Dijkstra).
 *
 * <p>Used for meeting-station recommendation (8.1) and as the fallback when the Kakao route (6.1) fails.
 * Failures are {@link com.mju.linkro.common.exception.BusinessException}s with these codes:
 * <ul>
 *   <li>{@code STATION_NOT_FOUND} (404): a station id does not exist</li>
 *   <li>{@code STATION_UNSUPPORTED} (422): a station exists but has {@code is_supported = false}</li>
 *   <li>{@code ROUTE_NOT_FOUND} (404): both stations are valid but the destination cannot be reached</li>
 * </ul>
 */
public interface SubwayRouteFinder {

    /**
     * Minimum-time route. When {@code fromStationId == toStationId} it returns an empty route (no steps, one
     * stop, all totals 0) instead of failing, so a participant who already stands at a candidate station counts
     * as 0 minutes.
     */
    RoutePath shortestPath(long fromStationId, long toStationId);

    /**
     * Minimum-time routes from one origin to every reachable station, for scoring many candidates at once.
     * Validates the origin like {@link #shortestPath}. The result includes the origin itself (empty route) and
     * only supported stations.
     */
    ShortestPathTree shortestPathsFrom(long fromStationId);
}
