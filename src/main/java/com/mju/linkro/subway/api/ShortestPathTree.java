package com.mju.linkro.subway.api;

import java.util.Optional;
import java.util.Set;

/** Result of {@link SubwayRouteFinder#shortestPathsFrom}. Immutable. */
public interface ShortestPathTree {

    /** Supported stations reachable from the origin, the origin included. */
    Set<Long> reachableStationIds();

    /**
     * Same route as {@code shortestPath(origin, stationId)}. Empty when the station is unreachable, unsupported
     * or unknown; it never throws.
     */
    Optional<RoutePath> pathTo(long stationId);
}
