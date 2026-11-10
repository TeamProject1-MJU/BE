package com.mju.linkro.recommendation.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A hand-made graph for the recommendation tests: each origin lists the route to every station it can reach. */
public final class Routes {
    private final Map<Long, Map<Long, RoutePath>> byOrigin = new HashMap<>();
    public int shortestPathsFromCalls = 0;

    /** Route from {@code from} to {@code to} (a reverse route is not implied). */
    public Routes route(long from, long to, int seconds, int transfers, int distanceM) {
        byOrigin.computeIfAbsent(from, id -> new HashMap<>()).put(to, path(from, to, seconds, transfers, distanceM));
        return this;
    }

    public static RoutePath path(long from, long to, int seconds, int transfers, int distanceM) {
        if (from == to) {
            return new RoutePath(List.of(), List.of(stop(from, null, 0)), 0, 0, 0, from, to);
        }
        PathStep ride = new PathStep(StepType.RIDE, 2L, "2호선", "#00A84D", DirectionCode.INNER, from, "역" + from, to, "역" + to,
                3, seconds, distanceM);
        return new RoutePath(List.of(ride), List.of(stop(from, DirectionCode.INNER, 0), stop(to, DirectionCode.INNER, seconds)),
                seconds, transfers, distanceM, from, to);
    }

    private static PathStop stop(long id, DirectionCode direction, int offset) {
        return new PathStop(id, "역" + id, 37.5, 127.0, id, 2L, direction, offset);
    }

    public SubwayRouteFinder finder() {
        return new SubwayRouteFinder() {
            @Override
            public RoutePath shortestPath(long fromStationId, long toStationId) {
                return byOrigin.get(fromStationId).get(toStationId);
            }

            @Override
            public ShortestPathTree shortestPathsFrom(long fromStationId) {
                shortestPathsFromCalls++;
                Map<Long, RoutePath> paths = byOrigin.get(fromStationId);
                if (paths == null) {
                    throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
                }
                return new ShortestPathTree() {
                    @Override
                    public Set<Long> reachableStationIds() {
                        return paths.keySet();
                    }

                    @Override
                    public Optional<RoutePath> pathTo(long stationId) {
                        return Optional.ofNullable(paths.get(stationId));
                    }
                };
            }
        };
    }
}
