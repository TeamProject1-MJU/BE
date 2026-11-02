package com.mju.linkro.subway.api;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract every {@link SubwayRouteFinder} must keep; the real graph implementation can extend it too. */
public abstract class SubwayRouteFinderContract {

    protected abstract SubwayRouteFinder finder();

    /** Two different supported stations connected to each other. */
    protected abstract long origin();

    protected abstract long destination();

    /** An id with no station. */
    protected abstract long missingStation();

    /** A valid station that {@link #origin()} cannot reach. */
    protected abstract long unreachableStation();

    @Test
    void shortestPathKeepsTotalsConsistentWithSteps() {
        RoutePath path = finder().shortestPath(origin(), destination());

        assertThat(path.fromStationId()).isEqualTo(origin());
        assertThat(path.toStationId()).isEqualTo(destination());
        assertThat(path.steps()).isNotEmpty();
        assertThat(path.stops().get(0).stationId()).isEqualTo(origin());
        assertThat(path.stops().get(path.stops().size() - 1).stationId()).isEqualTo(destination());
        assertThat(path.stops().get(0).offsetSec()).isZero();
        assertThat(path.stops()).isSortedAccordingTo((a, b) -> Integer.compare(a.offsetSec(), b.offsetSec()));
        assertThat(path.stops().get(path.stops().size() - 1).offsetSec()).isEqualTo(path.totalSeconds());
        assertThat(path.totalSeconds()).isEqualTo(path.steps().stream().mapToInt(PathStep::durationSec).sum());
        assertThat(path.rideDistanceM()).isEqualTo(path.steps().stream()
                .filter(step -> step.type() == StepType.RIDE).mapToInt(PathStep::distanceM).sum());
        assertThat(path.transferCount()).isEqualTo((int) path.steps().stream()
                .filter(step -> step.type() == StepType.TRANSFER).count());
    }

    @Test
    void sameStationIsAnEmptyRoute() {
        RoutePath path = finder().shortestPath(origin(), origin());

        assertThat(path.steps()).isEmpty();
        assertThat(path.stops()).singleElement().satisfies(stop -> {
            assertThat(stop.stationId()).isEqualTo(origin());
            assertThat(stop.offsetSec()).isZero();
        });
        assertThat(path.totalSeconds()).isZero();
        assertThat(path.transferCount()).isZero();
        assertThat(path.rideDistanceM()).isZero();
    }

    @Test
    void missingStationIsStationNotFound() {
        assertError(() -> finder().shortestPath(missingStation(), destination()), ErrorCode.STATION_NOT_FOUND);
        assertError(() -> finder().shortestPath(origin(), missingStation()), ErrorCode.STATION_NOT_FOUND);
        assertError(() -> finder().shortestPathsFrom(missingStation()), ErrorCode.STATION_NOT_FOUND);
    }

    @Test
    void unreachableStationIsRouteNotFound() {
        assertError(() -> finder().shortestPath(origin(), unreachableStation()), ErrorCode.ROUTE_NOT_FOUND);
        ShortestPathTree tree = finder().shortestPathsFrom(origin());
        assertThat(tree.reachableStationIds()).doesNotContain(unreachableStation());
        assertThat(tree.pathTo(unreachableStation())).isEmpty();
        assertThat(tree.pathTo(missingStation())).isEmpty();
    }

    @Test
    void treeMatchesShortestPathForEveryReachableStation() {
        ShortestPathTree tree = finder().shortestPathsFrom(origin());

        assertThat(tree.reachableStationIds()).contains(origin(), destination());
        for (long stationId : tree.reachableStationIds()) {
            assertThat(tree.pathTo(stationId)).contains(finder().shortestPath(origin(), stationId));
        }
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode()).isEqualTo(code);
    }
}
