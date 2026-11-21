package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The detour cap of #185 ({@code linkro.subway.graph.timetable-detour-cap-seconds}): a route needing fewer transfers may
 * take at most the cap longer than the quickest one, at the same leaving time (arrive-by, the replay) or the same arrival
 * (leaving time search, #107). Both hand-made timetables below differ by exactly 300 s, so one cap decides both alike.
 */
class TimetableRouterDetourTest {
    static final int DIFFERENCE = 300;

    static TimetableRouter router(SubwayGraphData graph, List<List<String>> trains, int capSeconds) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        trains.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        TrainTimetable timetable = TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), graph);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setTimetableDetourCapSeconds(capSeconds);
        return new TimetableRouter(SubwayGraph.of(graph), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                Duration.ofMinutes(60));
    }

    /**
     * Arrive-by: 가 -> 나 -> 다 -> 라 on 6호선, T a slow train (가 39800, 라 40400); at 다 a 20 s walk to the faster U on
     * 2호선 (다 39940, 라 40100). Leaving 가 at 39800 either way: T alone (no transfer) arrives 300 s after T and U.
     */
    static final SubwayGraphData ARRIVE_BY = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라")),
            List.of(new Node(61, 1, 6, "601", 1, "MAIN"), new Node(62, 2, 6, "602", 2, "MAIN"), new Node(63, 3, 6, "603", 3, "MAIN"),
                    new Node(64, 4, 6, "604", 4, "MAIN"), new Node(23, 3, 2, "203", 1, "MAIN"), new Node(24, 4, 2, "204", 2, "MAIN")),
            List.of(ride(1, 61, 62, DirectionCode.DOWN, 50), ride(2, 62, 63, DirectionCode.DOWN, 40), ride(3, 63, 64, DirectionCode.DOWN, 490),
                    ride(4, 23, 24, DirectionCode.DOWN, 160),
                    new Edge(5, 63, 23, StepType.TRANSFER, null, 20, 30), new Edge(6, 23, 63, StepType.TRANSFER, null, 20, 30)));

    static final List<List<String>> ARRIVE_BY_TRAINS = List.of(
            train("6호선", "T", "가", "라", "가:39800:39800", "나:39850:39860", "다:39900:39910", "라:40400:40400"),
            train("2호선", "U", "다", "라", "다:39940:39940", "라:40100:40100"));

    /**
     * Leaving time search (#107): from 가 the slow 6호선 train T6 (가 40000, 라 40600) or 2호선 W (가 40300, 다 40400), a
     * 20 s walk and T6 from 다 (40500): the same arrival, T6 alone leaving 300 s earlier.
     */
    static final SubwayGraphData LEAVE_AT = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라")),
            List.of(new Node(61, 1, 6, "601", 1, "MAIN"), new Node(62, 2, 6, "602", 2, "MAIN"), new Node(63, 3, 6, "603", 3, "MAIN"),
                    new Node(64, 4, 6, "604", 4, "MAIN"), new Node(21, 1, 2, "201", 1, "MAIN"), new Node(23, 3, 2, "203", 2, "MAIN")),
            List.of(ride(1, 61, 62, DirectionCode.DOWN, 200), ride(2, 62, 63, DirectionCode.DOWN, 270), ride(3, 63, 64, DirectionCode.DOWN, 100),
                    ride(4, 21, 23, DirectionCode.DOWN, 100),
                    new Edge(5, 63, 23, StepType.TRANSFER, null, 20, 30), new Edge(6, 23, 63, StepType.TRANSFER, null, 20, 30)));

    static final List<List<String>> LEAVE_AT_TRAINS = List.of(
            train("6호선", "T6", "가", "라", "가:40000:40000", "나:40200:40210", "다:40480:40500", "라:40600:40600"),
            train("2호선", "W", "가", "다", "가:40300:40300", "다:40400:40400"));

    static TimedRoute arriveBy(int cap) {
        return latest(router(ARRIVE_BY, ARRIVE_BY_TRAINS, cap), 1, 4, 40400);
    }

    static TimedRoute leaveAt(int cap) {
        return router(LEAVE_AT, LEAVE_AT_TRAINS, cap).departingAt(1, at(THURSDAY, 39900)).routeTo(4).orElseThrow();
    }

    @Test
    void arriveByKeepsTheRouteWithFewerTransfersWithinTheCap() {
        TimedRoute route = arriveBy(900);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 39800));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40400));
    }

    @Test
    void arriveByTakesTheQuickerRouteOverTheCap() {
        TimedRoute route = arriveBy(DIFFERENCE - 1);
        // The same leaving time, 300 s earlier, one transfer more.
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T", "U");
        assertThat(route.path().transferCount()).isEqualTo(1);
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 39800));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40100));
        assertStepsAddUp(route);
    }

    @Test
    void arriveByAtTheCapBoundaryZeroAndAHugeCap() {
        // Exactly the cap is within it; 0 lets no detour through; a huge cap never takes the quicker route.
        assertThat(arriveBy(DIFFERENCE).legs()).extracting(TimedLeg::trainCode).containsExactly("T");
        assertThat(arriveBy(0).legs()).extracting(TimedLeg::trainCode).containsExactly("T", "U");
        assertThat(arriveBy(Integer.MAX_VALUE).legs()).extracting(TimedLeg::trainCode).containsExactly("T");
    }

    @Test
    void leaveAtKeepsTheRouteWithFewerTransfersWithinTheCap() {
        TimedRoute route = leaveAt(900);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T6");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40000));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40600));
    }

    @Test
    void leaveAtTakesTheLaterLeavingRouteOverTheCap() {
        TimedRoute route = leaveAt(DIFFERENCE - 1);
        // The same arrival, leaving 300 s later, one transfer more (#107 searching back from 40600).
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("W", "T6");
        assertThat(route.path().transferCount()).isEqualTo(1);
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40300));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40600));
        assertStepsAddUp(route);
    }

    @Test
    void leaveAtAtTheCapBoundaryZeroAndAHugeCap() {
        assertThat(leaveAt(DIFFERENCE).legs()).extracting(TimedLeg::trainCode).containsExactly("T6");
        assertThat(leaveAt(0).legs()).extracting(TimedLeg::trainCode).containsExactly("W", "T6");
        assertThat(leaveAt(Integer.MAX_VALUE).legs()).extracting(TimedLeg::trainCode).containsExactly("T6");
    }

    @Test
    void bothSearchesDecideAlikeForOneCap() {
        for (int cap : new int[]{0, 1, DIFFERENCE - 1, DIFFERENCE, DIFFERENCE + 1, 900, Integer.MAX_VALUE}) {
            boolean fewerTransfers = cap >= DIFFERENCE;
            assertThat(arriveBy(cap).path().transferCount()).as("arrive-by, cap %d", cap).isEqualTo(fewerTransfers ? 0 : 1);
            assertThat(leaveAt(cap).path().transferCount()).as("leaving time, cap %d", cap).isEqualTo(fewerTransfers ? 0 : 1);
        }
    }

    @Test
    void aNegativeCapIsRejected() {
        assertThatThrownBy(() -> router(ARRIVE_BY, ARRIVE_BY_TRAINS, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timetable-detour-cap-seconds");
        SubwayGraphProperties costs = new SubwayGraphProperties();
        assertThat(costs.getTimetableDetourCapSeconds()).isEqualTo(900);
        costs.setTimetableDetourCapSeconds(-1);
        assertThatThrownBy(costs::validate).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timetable-detour-cap-seconds");
    }
}
