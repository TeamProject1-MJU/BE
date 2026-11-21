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
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #185: at a station that is not a junction both searches let only the first state settled there look for the next
 * trains. A state settled later that needs fewer transfers looks again, so a walk reaching the station first no longer
 * hides the train ridden through it. Times are compared first as before; the transfers decide only between equal times.
 */
class TimetableRouterPruneTest {

    static TimetableRouter router(SubwayGraphData graph, List<List<String>> trains) {
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
        return new TimetableRouter(SubwayGraph.of(graph), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                Duration.ofMinutes(60));
    }

    /**
     * Backward, like 구산 -> 신당 by 07:00 on a Saturday (#185 phase 1): 가 -> 나 -> 다 -> 라 on 6호선 (T), and from 다 a
     * walk of 20 s to 2호선 (U) that reaches 라 earlier. Searching back from 라, the walk state at 다 6호선 (220) is settled
     * before T riding through 다 (210), so T was found from 가 only with the walk's transfer.
     */
    static final SubwayGraphData BACKWARD = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라")),
            List.of(new Node(61, 1, 6, "601", 1, "MAIN"), new Node(62, 2, 6, "602", 2, "MAIN"), new Node(63, 3, 6, "603", 3, "MAIN"),
                    new Node(64, 4, 6, "604", 4, "MAIN"), new Node(23, 3, 2, "203", 1, "MAIN"), new Node(24, 4, 2, "204", 2, "MAIN")),
            List.of(ride(1, 61, 62, DirectionCode.DOWN, 50), ride(2, 62, 63, DirectionCode.DOWN, 40), ride(3, 63, 64, DirectionCode.DOWN, 190),
                    ride(4, 23, 24, DirectionCode.DOWN, 110),
                    new Edge(5, 63, 23, StepType.TRANSFER, null, 20, 30), new Edge(6, 23, 63, StepType.TRANSFER, null, 20, 30)));

    static final List<List<String>> BACKWARD_TRAINS = List.of(
            train("6호선", "T", "가", "라", "가:40100:40100", "나:40150:40160", "다:40200:40210", "라:40400:40400"),
            train("2호선", "U", "다", "라", "다:40240:40240", "라:40350:40350"));

    @Test
    void theBackwardSearchRidesTheTrainThroughInsteadOfTheWalkSettledFirst() {
        TimedRoute route = latest(router(BACKWARD, BACKWARD_TRAINS), 1, 4, 40400);
        // Before #185: T to 다, walk, U (one transfer, arriving 40350), leaving 가 at the same 40100.
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40100));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40400));
        assertStepsAddUp(route);
    }

    @Test
    void theBackwardSearchStillTakesTheLatestDeparture() {
        // A later 2호선 route wins on time: U2 leaves 다 at 40300, so T2 (가 40150) and the walk leave later than T.
        List<List<String>> trains = List.of(BACKWARD_TRAINS.get(0), BACKWARD_TRAINS.get(1),
                train("6호선", "T2", "가", "다", "가:40150:40150", "나:40200:40210", "다:40250:40250"),
                train("2호선", "U2", "다", "라", "다:40300:40300", "라:40390:40390"));
        TimedRoute route = latest(router(BACKWARD, trains), 1, 4, 40400);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T2", "U2");
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40150));
        assertThat(route.path().transferCount()).isEqualTo(1);
    }

    /**
     * Forward, like 신내 -> 독바위 at 13:20 (#185 phase 1): V 가 -> 나 (6호선, ends there), then T 나 -> 다 -> 라 on 6호선, or
     * walks at 나 and 다 around the faster U on 2호선, which reaches 다 6호선 (370) before T does (400) and boards T there.
     * The walk state at 다 was settled first and T riding through 다 was dropped: two transfers instead of none, the same
     * trains from 가 and to 라. The search back from 라 (#107) had the same issue at 나.
     */
    static final SubwayGraphData FORWARD = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라")),
            List.of(new Node(61, 1, 6, "601", 1, "MAIN"), new Node(62, 2, 6, "602", 2, "MAIN"), new Node(63, 3, 6, "603", 3, "MAIN"),
                    new Node(64, 4, 6, "604", 4, "MAIN"), new Node(22, 2, 2, "202", 1, "MAIN"), new Node(23, 3, 2, "203", 2, "MAIN")),
            List.of(ride(1, 61, 62, DirectionCode.DOWN, 100), ride(2, 62, 63, DirectionCode.DOWN, 140), ride(3, 63, 64, DirectionCode.DOWN, 90),
                    ride(4, 22, 23, DirectionCode.DOWN, 50),
                    new Edge(5, 62, 22, StepType.TRANSFER, null, 30, 30), new Edge(6, 22, 62, StepType.TRANSFER, null, 30, 30),
                    new Edge(7, 23, 63, StepType.TRANSFER, null, 20, 30), new Edge(8, 63, 23, StepType.TRANSFER, null, 20, 30)));

    static final List<List<String>> FORWARD_TRAINS = List.of(
            train("6호선", "V", "가", "나", "가:40100:40100", "나:40200:40200"),
            train("6호선", "T", "나", "라", "나:40260:40260", "다:40400:40410", "라:40500:40500"),
            train("2호선", "U", "나", "다", "나:40300:40300", "다:40350:40350"));

    @Test
    void theForwardSearchRidesTheTrainThroughInsteadOfTheWalkSettledFirst() {
        TimetableRouter router = router(FORWARD, FORWARD_TRAINS);
        TimedRoute route = router.departingAt(1, at(THURSDAY, 40000)).routeTo(4).orElseThrow();
        // Before #185: V, walk, U, walk, T (two transfers), the same 40100 and 40500.
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("V", "T");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40100));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40500));
        assertStepsAddUp(route);
        // The search back from 40500 agrees.
        Map<Long, TimedRoute> back = router.latestDepartures(4, at(THURSDAY, 40500), Set.of(1L), 0);
        assertThat(back.get(1L).legs()).extracting(TimedLeg::trainCode).containsExactly("V", "T");
        assertThat(back.get(1L).path().transferCount()).isZero();
    }

    @Test
    void theForwardSearchStillTakesTheEarliestArrival() {
        // U2 reaches 다 2호선 at 40320 and T3 leaves 다 at 40360: arriving at 40450 beats T at 40500, with two transfers.
        List<List<String>> trains = List.of(FORWARD_TRAINS.get(0), FORWARD_TRAINS.get(1),
                train("2호선", "U2", "나", "다", "나:40270:40270", "다:40320:40320"),
                train("6호선", "T3", "다", "라", "다:40360:40360", "라:40450:40450"));
        TimedRoute route = router(FORWARD, trains).departingAt(1, at(THURSDAY, 40000)).routeTo(4).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("V", "U2", "T3");
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40450));
        assertThat(route.path().transferCount()).isEqualTo(2);
    }
}
