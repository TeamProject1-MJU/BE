package com.mju.linkro.subway.mock;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockSubwayRouteFinderTest extends SubwayRouteFinderContract {
    private final MockSubwayRouteFinder finder = new MockSubwayRouteFinder();

    @Override
    protected SubwayRouteFinder finder() {
        return finder;
    }

    @Override
    protected long origin() {
        return 3;
    }

    @Override
    protected long destination() {
        return 7;
    }

    @Override
    protected long missingStation() {
        return 0;
    }

    @Override
    protected long unreachableStation() {
        return 1_000_000;
    }

    @Test
    void routeIsOneRidePassingIdsInOrder() {
        RoutePath path = finder.shortestPath(3, 7);

        assertThat(path.steps()).singleElement().satisfies(step -> {
            assertThat(step.type()).isEqualTo(StepType.RIDE);
            assertThat(step.direction()).isEqualTo(DirectionCode.DOWN);
            assertThat(step.stationCount()).isEqualTo(4);
            assertThat(step.durationSec()).isEqualTo(360);
            assertThat(step.distanceM()).isEqualTo(4_000);
        });
        assertThat(path.stops()).extracting(PathStop::stationId).containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(path.stops()).extracting(PathStop::offsetSec).containsExactly(0, 90, 180, 270, 360);
        assertThat(path.totalSeconds()).isEqualTo(360);
        assertThat(path.rideDistanceM()).isEqualTo(4_000);
        assertThat(path.transferCount()).isZero();
        assertThat(finder.shortestPath(7, 3).steps().get(0).direction()).isEqualTo(DirectionCode.UP);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 241, 999_999})
    void idsOutsideTheMockNetworkAreStationNotFound(long stationId) {
        assertThatThrownBy(() -> finder.shortestPath(1, stationId)).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(ErrorCode.STATION_NOT_FOUND);
    }

    @Test
    void isolatedStationReachesOnlyItself() {
        ShortestPathTree tree = finder.shortestPathsFrom(1_000_000);

        assertThat(tree.reachableStationIds()).containsExactly(1_000_000L);
        assertThat(tree.pathTo(1_000_000)).hasValueSatisfying(path -> assertThat(path.steps()).isEmpty());
        assertThat(tree.pathTo(1)).isEmpty();
        assertThat(finder.shortestPathsFrom(1).reachableStationIds()).hasSize(240);
    }
}
