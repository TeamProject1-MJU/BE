package com.mju.linkro.subway.mock;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/**
 * Fake but deterministic routes for development before the real graph exists.
 *
 * <ul>
 *   <li>Stations 1..{@value #STATION_COUNT} (as many as the V11 seed) form one line passed in id order.</li>
 *   <li>Every hop takes {@value #SECONDS_PER_HOP} s and {@value #METERS_PER_HOP} m; no transfers.</li>
 *   <li>Ids 0 or below, and ids between {@value #STATION_COUNT} and {@value #UNREACHABLE_FROM}, are
 *       STATION_NOT_FOUND.</li>
 *   <li>Ids from {@value #UNREACHABLE_FROM} exist but connect to nothing, to reproduce ROUTE_NOT_FOUND.</li>
 * </ul>
 */
public class MockSubwayRouteFinder implements SubwayRouteFinder {
    static final int STATION_COUNT = 240;
    static final long UNREACHABLE_FROM = 1_000_000L;
    static final int SECONDS_PER_HOP = 90;
    static final int METERS_PER_HOP = 1_000;
    static final long LINE_ID = 1L;
    static final String LINE_NAME = "1호선";
    static final String LINE_COLOR = "#0052A4";

    private static final Set<Long> NETWORK = LongStream.rangeClosed(1, STATION_COUNT).boxed()
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public RoutePath shortestPath(long fromStationId, long toStationId) {
        requireStation(fromStationId);
        requireStation(toStationId);
        if (fromStationId == toStationId) {
            return new RoutePath(List.of(), List.of(stop(fromStationId, null, 0)), 0, 0, 0, fromStationId, toStationId);
        }
        if (fromStationId >= UNREACHABLE_FROM || toStationId >= UNREACHABLE_FROM) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND);
        }
        int hops = (int) Math.abs(toStationId - fromStationId);
        long delta = fromStationId < toStationId ? 1 : -1;
        DirectionCode direction = delta > 0 ? DirectionCode.DOWN : DirectionCode.UP;
        List<PathStop> stops = new ArrayList<>(hops + 1);
        for (int i = 0; i <= hops; i++) {
            stops.add(stop(fromStationId + i * delta, direction, i * SECONDS_PER_HOP));
        }
        PathStep ride = new PathStep(StepType.RIDE, LINE_ID, LINE_NAME, LINE_COLOR, direction,
                fromStationId, name(fromStationId), toStationId, name(toStationId),
                hops, hops * SECONDS_PER_HOP, hops * METERS_PER_HOP);
        return new RoutePath(List.of(ride), stops, ride.durationSec(), 0, ride.distanceM(), fromStationId, toStationId);
    }

    @Override
    public ShortestPathTree shortestPathsFrom(long fromStationId) {
        requireStation(fromStationId);
        Set<Long> reachable = fromStationId >= UNREACHABLE_FROM ? Set.of(fromStationId) : NETWORK;
        return new ShortestPathTree() {
            @Override
            public Set<Long> reachableStationIds() {
                return reachable;
            }

            @Override
            public Optional<RoutePath> pathTo(long stationId) {
                return reachable.contains(stationId) ? Optional.of(shortestPath(fromStationId, stationId)) : Optional.empty();
            }
        };
    }

    private static void requireStation(long stationId) {
        if (stationId <= 0 || (stationId > STATION_COUNT && stationId < UNREACHABLE_FROM)) {
            throw new BusinessException(ErrorCode.STATION_NOT_FOUND);
        }
    }

    private static PathStop stop(long stationId, DirectionCode direction, int offsetSec) {
        // Mock node id equals the station id; coordinates stay inside Seoul for map previews.
        return new PathStop(stationId, name(stationId), 37.5 + (stationId % 300) * 0.001, 127.0, stationId,
                LINE_ID, direction, offsetSec);
    }

    private static String name(long stationId) {
        return "목업역" + stationId;
    }
}
