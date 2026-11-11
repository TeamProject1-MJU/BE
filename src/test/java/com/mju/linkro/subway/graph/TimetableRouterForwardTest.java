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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.THURSDAY;
import static com.mju.linkro.subway.graph.TimetableRouterTest.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Forward routes that arrive at the same time with fewer transfers (#107), on a hand-made graph shaped like
 * 불광 -> 디지털미디어시티: 6호선 가(1) -> 나(2) -> 다(3), and 3호선 가(1) -> 나(2) with a 30 s walk to 6호선 at 나.
 * A 3호선 train reaches 나 before the 6호선 train that left 가 a little later, so the earliest state at 나 is the walk.
 */
class TimetableRouterForwardTest {
    static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new Line(3, "3", "3호선", "#EF7C1C", 3), new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다")),
            List.of(new Node(61, 1, 6, "601", 1, "MAIN"), new Node(62, 2, 6, "602", 2, "MAIN"), new Node(63, 3, 6, "603", 3, "MAIN"),
                    new Node(31, 1, 3, "301", 1, "MAIN"), new Node(32, 2, 3, "302", 2, "MAIN")),
            List.of(ride(1, 61, 62), ride(2, 62, 63), ride(3, 31, 32),
                    new Edge(10, 32, 62, StepType.TRANSFER, null, 30, 40), new Edge(11, 62, 32, StepType.TRANSFER, null, 30, 40)));

    static Station station(long id, String name) {
        return new Station(id, name, 37.6, 126.9, true);
    }

    static Edge ride(long id, long from, long to) {
        return new Edge(id, from, to, StepType.RIDE, DirectionCode.DOWN, 100, 1000);
    }

    static List<String> train(String line, String code, String... stops) {
        List<String> rows = new ArrayList<>();
        for (String stop : stops) {
            String[] f = stop.split(":");
            rows.add(String.join(",", line, f[0], "평일", "하행", "일반", code, f[1], f[2], "가", "다"));
        }
        return rows;
    }

    static TimetableRouter router(List<List<String>> trains) {
        StringBuilder csv = new StringBuilder(TimetableRouterTest.HEADER).append('\n');
        trains.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        return new TimetableRouter(SubwayGraph.of(DATA), TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA),
                new TimetableClock(new HolidayCalendar("")), costs, 0, Duration.ofMinutes(60));
    }

    // 3호선 T1 가 30010 -> 나 30060 (walk 30 s: 6호선 나 at 30090); 6호선 S1 가 30030 -> 나 30120/30130 -> 다 30230.
    static final List<String> T1 = train("3호선", "T1", "가:30010:30010", "나:30060:30060");
    static final List<String> S1 = train("6호선", "S1", "가:30030:30030", "나:30120:30130", "다:30230:30230");

    @Test
    void theSameTrainFromTheOriginReplacesTheWalkToIt() {
        // Before #107 the forward search kept T1 -> walk -> S1 (1 transfer): the walk reaches 6호선 나 at 30090, before S1
        // (30120). S1 straight from 가 arrives at the same 30230 without a transfer.
        TimedRoute route = router(List.of(T1, S1)).departingAt(1, at(THURSDAY, 30000)).routeTo(3).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("S1");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 30230));
        assertThat(route.leaveAt()).isEqualTo(at(THURSDAY, 30030));
    }

    @Test
    void anAlreadyBestForwardRouteStaysAsItIs() {
        // Leaving after T1: the forward route is S1 already.
        TimedRoute route = router(List.of(T1, S1)).departingAt(1, at(THURSDAY, 30020)).routeTo(3).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("S1");
        // Only 3호선 then a later 6호선 train from 나: nothing better exists, the forward route is kept.
        List<String> fromNa = train("6호선", "S2", "나:30100:30100", "다:30200:30200");
        TimedRoute transfer = router(List.of(T1, fromNa)).departingAt(1, at(THURSDAY, 30000)).routeTo(3).orElseThrow();
        assertThat(transfer.legs()).extracting(TimedLeg::trainCode).containsExactly("T1", "S2");
        assertThat(transfer.path().transferCount()).isEqualTo(1);
    }

    @Test
    void aRouteLeavingBeforeTheRequestedTimeIsNotTaken() {
        // S1 leaves 가 at 30030, before the request (30031): it has no transfer and arrives as early, but cannot be taken.
        // T2 reaches 6호선 나 at 30140 (after the walk), too late for S1 there (30130), so S3 is the forward route.
        List<String> t2 = train("3호선", "T2", "가:30040:30040", "나:30110:30110");
        List<String> s3 = train("6호선", "S3", "나:30200:30200", "다:30230:30230");
        TimedRoute route = router(List.of(S1, t2, s3)).departingAt(1, at(THURSDAY, 30031)).routeTo(3).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("T2", "S3");
        assertThat(route.path().transferCount()).isEqualTo(1);
        assertThat(route.leaveAt()).isAfterOrEqualTo(at(THURSDAY, 30031));
    }
}
