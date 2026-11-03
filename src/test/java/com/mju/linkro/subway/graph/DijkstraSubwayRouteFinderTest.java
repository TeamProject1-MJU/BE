package com.mju.linkro.subway.graph;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class DijkstraSubwayRouteFinderTest {
    private final DijkstraSubwayRouteFinder basic = new DijkstraSubwayRouteFinder(GraphFixture.basic().graph(), GraphFixture.costs());
    private final DijkstraSubwayRouteFinder junctions = finder(GraphFixture.costs());

    private static DijkstraSubwayRouteFinder finder(SubwayGraphProperties costs) {
        return new DijkstraSubwayRouteFinder(GraphFixture.junctions().graph(), costs);
    }

    @Test
    void consecutiveRidesOfOneLineAndDirectionAreOneStep() {
        RoutePath path = basic.shortestPath(1, 4);

        assertThat(path.steps()).singleElement().satisfies(step -> {
            assertThat(step.type()).isEqualTo(StepType.RIDE);
            assertThat(step.lineId()).isEqualTo(1L);
            assertThat(step.lineName()).isEqualTo("1호선");
            assertThat(step.color()).isEqualTo("#000001");
            assertThat(step.direction()).isEqualTo(DirectionCode.DOWN);
            assertThat(step.fromStationName()).isEqualTo("A");
            assertThat(step.toStationName()).isEqualTo("D");
            assertThat(step.stationCount()).isEqualTo(3);
            assertThat(step.durationSec()).isEqualTo(300);
            assertThat(step.distanceM()).isEqualTo(3000);
        });
        assertThat(path.stops()).extracting(PathStop::stationLineId).containsExactly(11L, 21L, 31L, 41L);
        assertThat(path.stops()).extracting(PathStop::offsetSec).containsExactly(0, 100, 200, 300);
        // Every stop rides DOWN; the last stop keeps the direction of the segment that reached it.
        assertThat(path.stops()).extracting(PathStop::direction).containsOnly(DirectionCode.DOWN);
        assertThat(path.stops().get(0).latitude()).isEqualTo(37.501);
        assertThat(path.totalSeconds()).isEqualTo(300);
        assertThat(path.rideDistanceM()).isEqualTo(3000);
        assertThat(path.transferCount()).isZero();
    }

    @Test
    void transferIsAStepAndItsTimeCountsButNotItsDistance() {
        // B -> C on L1 (100 s), transfer to L2 (60 s, 50 m), C -> E on L2 (100 s).
        RoutePath path = basic.shortestPath(2, 5);

        assertThat(path.steps()).extracting(PathStep::type).containsExactly(StepType.RIDE, StepType.TRANSFER, StepType.RIDE);
        PathStep transfer = path.steps().get(1);
        assertThat(transfer.lineId()).isEqualTo(2L);
        assertThat(transfer.direction()).isNull();
        assertThat(transfer.fromStationId()).isEqualTo(3L);
        assertThat(transfer.toStationId()).isEqualTo(3L);
        assertThat(transfer.durationSec()).isEqualTo(60);
        assertThat(transfer.distanceM()).isEqualTo(50);
        assertThat(path.totalSeconds()).isEqualTo(260);
        assertThat(path.transferCount()).isEqualTo(1);
        assertThat(path.rideDistanceM()).isEqualTo(2000);
        assertThat(path.stops()).extracting(PathStop::stationLineId).containsExactly(21L, 31L, 32L, 52L);
        assertThat(path.stops()).extracting(PathStop::offsetSec).containsExactly(0, 100, 160, 260);
        assertThat(path.stops()).extracting(PathStop::direction)
                .containsExactly(DirectionCode.DOWN, DirectionCode.DOWN, DirectionCode.DOWN, DirectionCode.DOWN);
    }

    @Test
    void tieOnTimePrefersFewerTransfers() {
        // A -> E: L1+transfer+L2 = 100+100+60+100 = 360 s with one transfer, L3 direct = 360 s without.
        RoutePath path = basic.shortestPath(1, 5);

        assertThat(path.totalSeconds()).isEqualTo(360);
        assertThat(path.transferCount()).isZero();
        assertThat(path.steps()).singleElement().extracting(PathStep::lineId).isEqualTo(3L);
        assertThat(basic.shortestPath(1, 5)).isEqualTo(path);
    }

    @Test
    void configuredWaitsAreAddedPerRideAndTransfer() {
        SubwayGraphProperties costs = GraphFixture.costs();
        costs.setDwellSeconds(10);
        costs.setTransferWaitSeconds(30);
        RoutePath path = new DijkstraSubwayRouteFinder(GraphFixture.basic().graph(), costs).shortestPath(2, 5);

        // (100 + 10) + (60 + 30) + (100 + 10)
        assertThat(path.totalSeconds()).isEqualTo(310);
        assertThat(path.steps()).extracting(PathStep::durationSec).containsExactly(110, 90, 110);
    }

    @Test
    void sameStationIsEmptyAndUnreachableOrInvalidStationsFail() {
        RoutePath same = basic.shortestPath(3, 3);
        assertThat(same.steps()).isEmpty();
        assertThat(same.stops()).singleElement().satisfies(stop -> {
            assertThat(stop.stationLineId()).isEqualTo(31L);
            assertThat(stop.direction()).isNull();
        });

        assertError(() -> basic.shortestPath(1, 9), ErrorCode.ROUTE_NOT_FOUND);
        assertThat(basic.shortestPathsFrom(1).pathTo(9)).isEmpty();
        assertThat(basic.shortestPathsFrom(9).reachableStationIds()).containsExactly(9L);
        assertError(() -> basic.shortestPath(1, 999), ErrorCode.STATION_NOT_FOUND);
        assertError(() -> basic.shortestPath(8, 1), ErrorCode.STATION_UNSUPPORTED);
        assertError(() -> basic.shortestPath(1, 8), ErrorCode.STATION_UNSUPPORTED);
        assertError(() -> basic.shortestPathsFrom(8), ErrorCode.STATION_UNSUPPORTED);
        assertThat(basic.shortestPathsFrom(1).reachableStationIds()).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(basic.shortestPathsFrom(1).pathTo(8)).isEmpty();
    }

    @Test
    void seongsuBranchNeedsATransferButTheLoopRunsThrough() {
        // 뚝섬 -> 성수 (60) + branch transfer 19 + 성수 -> 용답 (180) = 259
        RoutePath branch = junctions.shortestPath(101, 104);
        assertThat(branch.totalSeconds()).isEqualTo(259);
        assertThat(branch.transferCount()).isEqualTo(1);
        assertThat(branch.steps()).extracting(PathStep::type).containsExactly(StepType.RIDE, StepType.TRANSFER, StepType.RIDE);
        PathStep transfer = branch.steps().get(1);
        assertThat(transfer.fromStationName()).isEqualTo("성수");
        assertThat(transfer.toStationName()).isEqualTo("성수");
        assertThat(transfer.lineId()).isEqualTo(2L);
        assertThat(transfer.durationSec()).isEqualTo(19);
        assertThat(transfer.distanceM()).isEqualTo(23);
        assertThat(branch.rideDistanceM()).isEqualTo(800 + 2300);
        // Same node twice: alighting INNER, boarding DOWN.
        assertThat(branch.stops()).extracting(PathStop::stationLineId).containsExactly(1012L, 1022L, 1022L, 1042L);
        assertThat(branch.stops()).extracting(PathStop::direction)
                .containsExactly(DirectionCode.INNER, DirectionCode.INNER, DirectionCode.DOWN, DirectionCode.DOWN);
        assertThat(branch.stops()).extracting(PathStop::offsetSec).containsExactly(0, 60, 79, 259);

        assertNoTransfer(junctions.shortestPath(101, 103), 150);
        assertNoTransfer(junctions.shortestPath(102, 104), 180);
        assertNoTransfer(junctions.shortestPath(104, 102), 180);
    }

    @Test
    void sinjeongBranchNeedsATransfer() {
        // 대림 -> 신도림 (150) + 68 + 신도림 -> 도림천 (90) = 308
        RoutePath branch = junctions.shortestPath(111, 114);
        assertThat(branch.totalSeconds()).isEqualTo(308);
        assertThat(branch.transferCount()).isEqualTo(1);
        assertThat(branch.steps().get(1).durationSec()).isEqualTo(68);
        assertThat(branch.steps().get(1).distanceM()).isEqualTo(81);
        assertThat(junctions.shortestPath(114, 113).totalSeconds()).isEqualTo(90 + 68 + 120);
        assertNoTransfer(junctions.shortestPath(111, 113), 270);
    }

    @Test
    void gangdongOnlyMacheonToHanamNeedsATransfer() {
        // 둔촌동 -> 강동 (110) + 16 + 강동 -> 길동 (90) = 216
        RoutePath macheonToHanam = junctions.shortestPath(124, 123);
        assertThat(macheonToHanam.totalSeconds()).isEqualTo(216);
        assertThat(macheonToHanam.transferCount()).isEqualTo(1);
        assertThat(junctions.shortestPath(123, 124).transferCount()).isEqualTo(1);
        assertNoTransfer(junctions.shortestPath(124, 121), 180);
        assertNoTransfer(junctions.shortestPath(121, 124), 180);
        assertNoTransfer(junctions.shortestPath(121, 123), 160);
    }

    @Test
    void eungamLoopRunsThroughAndOnlyOneWay() {
        // Loop -> main: 구산 -> 응암 (120) -> 새절 (80).
        assertNoTransfer(junctions.shortestPath(134, 132), 200);
        // Main -> loop: 새절 -> 응암 (80) -> 역촌 (100).
        assertNoTransfer(junctions.shortestPath(132, 133), 180);
        // One-way loop: 역촌 -> 응암 goes round via 구산 (100 + 120); loop -> loop at 응암 is through running.
        RoutePath round = junctions.shortestPath(133, 131);
        assertNoTransfer(round, 220);
        assertThat(round.stops()).extracting(PathStop::stationName).containsExactly("역촌", "구산", "응암");
        assertNoTransfer(junctions.shortestPath(134, 133), 220);
        assertThat(junctions.shortestPath(134, 132).steps()).extracting(PathStep::direction)
                .containsExactly(DirectionCode.LOOP, DirectionCode.DOWN);
    }

    @Test
    void lineTwoLoopUsesInnerAndOuterDirections() {
        assertThat(junctions.shortestPath(141, 143).steps()).singleElement().satisfies(step -> {
            assertThat(step.direction()).isEqualTo(DirectionCode.OUTER);
            assertThat(step.durationSec()).isEqualTo(100);
        });
        assertThat(junctions.shortestPath(143, 141).steps()).singleElement().extracting(PathStep::direction)
                .isEqualTo(DirectionCode.INNER);
        assertThat(junctions.shortestPath(141, 142).steps()).singleElement().extracting(PathStep::direction)
                .isEqualTo(DirectionCode.INNER);
    }

    @Test
    void branchTransferSettingsAreApplied(CapturedOutput output) {
        SubwayGraphProperties costs = GraphFixture.costs();
        costs.setBranchTransferWaitSeconds(30);
        costs.setBranchTransfers(new java.util.ArrayList<>(List.of(new SubwayGraphProperties.BranchTransfer("성수", "2", 40, 10))));
        costs.setDefaultBranchTransferSeconds(50);
        DijkstraSubwayRouteFinder custom = finder(costs);

        // 성수 from settings: 60 + (40 + 30) + 180.
        assertThat(custom.shortestPath(101, 104).totalSeconds()).isEqualTo(310);
        assertThat(custom.shortestPath(101, 104).steps().get(1).distanceM()).isEqualTo(10);
        // 신도림 missing from settings: default 50 s and 0 m, plus the 30 s wait.
        RoutePath fallback = custom.shortestPath(111, 114);
        assertThat(fallback.totalSeconds()).isEqualTo(150 + 80 + 90);
        assertThat(fallback.steps().get(1).distanceM()).isZero();
        assertThat(output.getAll()).contains("No branch transfer time for junction 신도림 line 2; using 50 s, 0 m")
                .contains("No branch transfer time for junction 강동 line 5; using 50 s, 0 m")
                .doesNotContain("junction 성수 line 2; using").doesNotContain("junction 응암 line 6; using");
    }

    @Test
    void junctionMissingFromTheRuleTableRunsThroughWithAWarning(CapturedOutput output) {
        DijkstraSubwayRouteFinder finder = finder(GraphFixture.costs());

        assertThat(output.getAll()).contains("Subway junction 분기역 line 9 is not in the branch rule table");
        assertNoTransfer(finder.shortestPath(151, 154), 200);
        assertNoTransfer(finder.shortestPath(154, 153), 200);
    }

    private static void assertNoTransfer(RoutePath path, int seconds) {
        assertThat(path.totalSeconds()).isEqualTo(seconds);
        assertThat(path.transferCount()).isZero();
        assertThat(path.steps()).extracting(PathStep::type).containsOnly(StepType.RIDE);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
