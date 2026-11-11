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
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.THURSDAY;
import static com.mju.linkro.subway.graph.TimetableRouterTest.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응암 loop -> loop changes (#104) on a hand-made 6호선: 구산 -> 응암 -> 역촌 (loop), 응암 <-> 새절 (main). No train of
 * the timetable goes round twice, so a 구산 train reaching 응암 needs another train for 역촌, at least the 응암 branch-transfers
 * time later (60 s as in subway-graph-defaults.properties, provisional), or default-branch-transfer-seconds (120 s) without it.
 */
class TimetableRouterEungamTest {
    static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new Line(6, "6", "6호선", "#CD7C2F", 6)),
            List.of(station(1, "응암"), station(2, "새절"), station(3, "역촌"), station(4, "구산")),
            List.of(new Node(11, 1, 6, "2611", 1, "MAIN"), new Node(12, 2, 6, "2618", 2, "MAIN"),
                    new Node(13, 3, 6, "2612", 1, "EUNGAM_LOOP"), new Node(14, 4, 6, "2617", 5, "EUNGAM_LOOP")),
            List.of(ride(1, 11, 13, DirectionCode.LOOP), ride(2, 14, 11, DirectionCode.LOOP),
                    ride(3, 11, 12, DirectionCode.DOWN), ride(4, 12, 11, DirectionCode.UP)));

    static Station station(long id, String name) {
        return new Station(id, name, 37.6, 126.9, true);
    }

    static Edge ride(long id, long from, long to, DirectionCode direction) {
        return new Edge(id, from, to, StepType.RIDE, direction, 100, 1000);
    }

    static List<String> train(String code, String origin, String terminal, String... stops) {
        List<String> rows = new ArrayList<>();
        for (String stop : stops) {
            String[] f = stop.split(":");
            rows.add(String.join(",", "6호선", f[0], "평일", "하행", "일반", code, f[1], f[2], origin, terminal));
        }
        return rows;
    }

    static TimetableRouter router(List<List<String>> trains) {
        return router(trains, List.of(new SubwayGraphProperties.BranchTransfer("응암", "6", 60, 0)));
    }

    static TimetableRouter router(List<List<String>> trains, List<SubwayGraphProperties.BranchTransfer> branchTransfers) {
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
        costs.setBranchTransfers(branchTransfers);
        return new TimetableRouter(SubwayGraph.of(DATA), TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA),
                new TimetableClock(new HolidayCalendar("")), costs, 0, Duration.ofMinutes(60));
    }

    // 6053-like: 구산 29880 -> 응암 30000 -> 새절 (goes on to the main line, not round the loop).
    static final List<String> FROM_GUSAN = train("6053", "응암", "봉화산", "구산:29870:29880", "응암:30000:30030", "새절:30110:30140");
    // Loop trains leaving 응암 for 역촌 0 s (same second as the arrival), 30 s, 90 s and 180 s after it.
    static final List<List<String>> LOOP = List.of(
            train("6059", "응암", "봉화산", "응암:30000:30000", "역촌:30100:30130"),
            train("6061", "응암", "봉화산", "응암:30030:30030", "역촌:30130:30160"),
            train("6063", "응암", "봉화산", "응암:30090:30090", "역촌:30190:30220"),
            train("6065", "응암", "봉화산", "응암:30180:30180", "역촌:30280:30310"));

    static List<List<String>> trains(List<String>... extra) {
        List<List<String>> all = new ArrayList<>(LOOP);
        all.add(FROM_GUSAN);
        all.addAll(List.of(extra));
        return all;
    }

    @Test
    void forwardNoLongerChangesInZeroSeconds() {
        TimedRoute route = router(trains()).departingAt(4, at(THURSDAY, 29800)).routeTo(3).orElseThrow();
        // Arrive 응암 30000: 6059 (0 s) and 6061 (30 s) are inside 60 s; 6063 (90 s) is the first one possible.
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("6053", "6063");
        assertThat(route.legs().get(1).waitSec()).isEqualTo(90);
        assertThat(route.legs().get(1).walkSec()).isZero();
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 30190));
        // A wait on the same line: no transfer, no TRANSFER step.
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.path().steps()).extracting(PathStep::type).containsOnly(StepType.RIDE);
        assertThat(route.path().steps().stream().mapToInt(PathStep::durationSec).sum()).isEqualTo(route.path().totalSeconds());
    }

    @Test
    void backwardKeepsTheChangeTimeToo() {
        // Reach 역촌 by 30160 (6061, 30 s after 6053 reaches 응암): too short, and nothing earlier leaves 구산.
        assertThat(router(trains()).latestDepartures(3, at(THURSDAY, 30160), Set.of(4L), 0)).isEmpty();
        // By 30190 (6063, 90 s after): 6053 then 6063, leaving 구산 at 29880.
        TimedRoute route = router(trains()).latestDepartures(3, at(THURSDAY, 30190), Set.of(4L), 0).get(4L);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("6053", "6063");
        assertThat(route.leaveAt()).isEqualTo(at(THURSDAY, 29880));
    }

    @Test
    void withoutAnEungamEntryTheDefaultBranchTransferTimeStillApplies() {
        // The existing fallback is unchanged: no 응암 branch-transfers entry means default-branch-transfer-seconds (120 s),
        // so 6063 (90 s) is too short and 6065 (180 s) is taken.
        TimedRoute route = router(trains(), List.of()).departingAt(4, at(THURSDAY, 29800)).routeTo(3).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("6053", "6065");
        assertThat(route.legs().get(1).waitSec()).isEqualTo(180);
    }

    @Test
    void aTrainThatWentRoundAgainWouldNotWait() {
        // Hypothetical: one train 구산 -> 응암 -> 역촌. Staying on board is not a change, so no minimum applies.
        List<String> roundAgain = train("6099", "응암", "응암", "구산:29870:29880", "응암:30000:30010", "역촌:30110:30140");
        TimedRoute route = router(List.of(roundAgain)).departingAt(4, at(THURSDAY, 29800)).routeTo(3).orElseThrow();
        assertThat(route.legs()).singleElement().extracting(TimedLeg::trainCode).isEqualTo("6099");
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 30110));
    }

    @Test
    void mainToLoopIsUnchanged() {
        // 새절 -> 응암 (응암행) -> 역촌: MAIN+ -> loop is not a timetable-only change, so 6059 at the same second still fits.
        List<String> fromSaejeol = train("6046", "봉화산", "응암", "새절:29880:29910", "응암:30000:30000");
        TimedRoute route = router(List.of(fromSaejeol, LOOP.get(0))).departingAt(2, at(THURSDAY, 29800)).routeTo(3).orElseThrow();
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("6046", "6059");
        assertThat(route.legs().get(1).waitSec()).isZero();
    }
}
