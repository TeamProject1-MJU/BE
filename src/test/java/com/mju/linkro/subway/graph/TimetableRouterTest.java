package com.mju.linkro.subway.graph;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Time-dependent routing on a hand-made graph and timetable (#96). Weekday trains of 2026-11-12 (Thursday).
 *
 * <pre>
 * 2호선 (one-way INNER loop): 가(1) -> 나(2) -> 다(3) -> 마(5) -> 가
 * 3호선: 나(2) <-> 라(4), transfer 나 2호선 <-> 3호선 60 s
 * 5호선: 천호(12) <-> 강동(10) <-> 길동(11), 강동 -> 둔촌동(13, 마천지선); branch change at 강동 16 s
 * </pre>
 */
public class TimetableRouterTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final LocalDate THURSDAY = LocalDate.of(2026, 11, 12);

    public static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(3, "3", "3호선", "#EF7C1C", 3),
                    new Line(5, "5", "5호선", "#996CAC", 5)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라"), station(5, "마"),
                    station(10, "강동"), station(11, "길동"), station(12, "천호"), station(13, "둔촌동")),
            List.of(new Node(21, 1, 2, "201", 1, "MAIN"), new Node(22, 2, 2, "202", 2, "MAIN"),
                    new Node(23, 3, 2, "203", 3, "MAIN"), new Node(25, 5, 2, "205", 4, "MAIN"),
                    new Node(32, 2, 3, "302", 1, "MAIN"), new Node(34, 4, 3, "304", 2, "MAIN"),
                    new Node(50, 10, 5, "548", 48, "MAIN"), new Node(51, 11, 5, "549", 49, "MAIN"),
                    new Node(52, 12, 5, "547", 47, "MAIN"), new Node(53, 13, 5, "552", 1, "MACHEON")),
            List.of(ride(1, 21, 22, DirectionCode.INNER, 90), ride(2, 22, 23, DirectionCode.INNER, 90),
                    ride(3, 23, 25, DirectionCode.INNER, 90), ride(4, 25, 21, DirectionCode.INNER, 90),
                    ride(5, 32, 34, DirectionCode.DOWN, 90), ride(6, 34, 32, DirectionCode.UP, 90),
                    ride(7, 52, 50, DirectionCode.DOWN, 70), ride(8, 50, 53, DirectionCode.DOWN, 110),
                    ride(9, 51, 50, DirectionCode.UP, 90), ride(10, 50, 51, DirectionCode.DOWN, 90),
                    ride(11, 53, 50, DirectionCode.UP, 110), ride(12, 50, 52, DirectionCode.UP, 70),
                    new Edge(20, 22, 32, StepType.TRANSFER, null, 60, 80), new Edge(21, 32, 22, StepType.TRANSFER, null, 60, 80)));

    static final String HEADER = "line,station,daytype,direction,express,train_code,arrive_sec,depart_sec,origin,terminal";

    static Station station(long id, String name) {
        return new Station(id, name, 37.5, 127.0, true);
    }

    static Edge ride(long id, long from, long to, DirectionCode direction, int seconds) {
        return new Edge(id, from, to, StepType.RIDE, direction, seconds, 1000);
    }

    /** Weekday train; each stop is "name:arrive:depart". */
    static List<String> train(String line, String code, String origin, String terminal, String... stops) {
        List<String> rows = new ArrayList<>();
        for (String stop : stops) {
            String[] f = stop.split(":");
            rows.add(String.join(",", line, f[0], "평일", "하행", "일반", code, f[1], f[2], origin, terminal));
        }
        return rows;
    }

    static final List<List<String>> TRAINS = List.of(
            // 2호선
            train("2호선", "L1", "가", "다", "가:30000:30000", "나:30090:30120", "다:30210:30210"),
            train("2호선", "L2", "가", "다", "가:30600:30600", "나:30690:30720", "다:30810:30810"),
            train("2호선", "S1", "가", "나", "가:31000:31000", "나:31090:31090"),
            train("2호선", "S2", "나", "다", "나:31300:31300", "다:31390:31390"),
            // A loop train passing 다 twice.
            train("2호선", "R1", "다", "다", "다:32000:32000", "마:32090:32100", "가:32190:32200", "나:32290:32300", "다:32390:32390"),
            // After midnight: 24:01:40.
            train("2호선", "N1", "가", "나", "가:86500:86500", "나:86590:86590"),
            // 3호선
            train("3호선", "T1", "나", "라", "나:30300:30300", "라:30390:30390"),
            train("3호선", "T2", "나", "라", "나:30900:30900", "라:30990:30990"),
            // 5호선: M1 runs 천호 -> 강동 -> 둔촌동 (through to 마천), H1 길동 -> 강동 -> 천호.
            train("5호선", "M1", "방화", "마천", "천호:40000:40000", "강동:40070:40080", "둔촌동:40190:40190"),
            train("5호선", "H1", "하남검단산", "방화", "길동:40000:40000", "강동:40090:40100", "천호:40170:40170"),
            train("5호선", "M3", "강동", "마천", "강동:40100:40100", "둔촌동:40210:40210"),
            train("5호선", "M2", "방화", "마천", "천호:40500:40500", "강동:40570:40580", "둔촌동:40690:40690"));

    public static TimetableRouter router() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        TRAINS.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        TrainTimetable timetable = TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setBranchTransfers(List.of(new SubwayGraphProperties.BranchTransfer("강동", "5", 16, 19)));
        return new TimetableRouter(SubwayGraph.of(DATA), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                java.time.Duration.ofMinutes(60));
    }

    public static Instant at(LocalDate date, int second) {
        return date.atStartOfDay(ZONE).toInstant().plusSeconds(second);
    }

    static TimedRoute latest(TimetableRouter router, long from, long to, int arriveBySec) {
        Map<Long, TimedRoute> routes = router.latestDepartures(to, at(THURSDAY, arriveBySec), Set.of(from), 0);
        assertThat(routes).containsKey(from);
        return routes.get(from);
    }

    static void assertStepsAddUp(TimedRoute route) {
        assertThat(route.path().steps().stream().mapToInt(PathStep::durationSec).sum()).isEqualTo(route.path().totalSeconds());
        assertThat(route.path().totalSeconds()).isEqualTo((int) (route.arriveAt().getEpochSecond() - route.boardAt().getEpochSecond()));
        assertThat(route.path().stops().get(route.path().stops().size() - 1).offsetSec()).isEqualTo(route.path().totalSeconds());
    }

    @Test
    void directRideTakesTheLatestTrainThatArrivesInTime() {
        TimetableRouter router = router();
        // L2 reaches 다 at 30810: exactly on time takes it, one second earlier only L1 fits.
        TimedRoute route = latest(router, 1, 3, 30810);
        assertThat(route.legs()).singleElement().satisfies(leg -> {
            assertThat(leg.trainCode()).isEqualTo("L2");
            assertThat(leg.stationCount()).isEqualTo(2);
            assertThat(leg.waitSec()).isZero();
            assertThat(leg.boardAt()).isEqualTo(at(THURSDAY, 30600));
            assertThat(leg.alightAt()).isEqualTo(at(THURSDAY, 30810));
        });
        assertThat(route.leaveAt()).isEqualTo(at(THURSDAY, 30600));
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.path().steps()).singleElement().extracting(PathStep::stationCount, PathStep::durationSec).containsExactly(2, 210);
        assertStepsAddUp(route);
        assertThat(latest(router, 1, 3, 30809).legs().get(0).trainCode()).isEqualTo("L1");
        assertThat(latest(router, 1, 3, 30811).legs().get(0).trainCode()).isEqualTo("L2");
    }

    @Test
    void oneTransferWaitsForTheNextTrainAfterTheWalk() {
        TimetableRouter router = router();
        // 가 -> 나 on L2 (30690), walk 60 s, T2 leaves 나 at 30900, 라 at 30990.
        TimedRoute route = latest(router, 1, 4, 30990);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("L2", "T2");
        assertThat(route.legs().get(1).walkSec()).isEqualTo(60);
        assertThat(route.legs().get(1).waitSec()).isEqualTo(30900 - 30690 - 60);
        assertThat(route.path().transferCount()).isEqualTo(1);
        assertThat(route.path().steps()).extracting(PathStep::type, PathStep::durationSec)
                .containsExactly(tuple(StepType.RIDE, 90), tuple(StepType.TRANSFER, 210), tuple(StepType.RIDE, 90));
        assertThat(route.path().steps().get(1).lineId()).isEqualTo(3L);
        assertStepsAddUp(route);
        // 라 by 30989 misses T2; T1 (30300) needs 나 by 30240: L1 is at 나 30090.
        assertThat(latest(router, 1, 4, 30989).legs()).extracting(TimedLeg::trainCode).containsExactly("L1", "T1");
    }

    @Test
    void stayingOnOneTrainWaitsNothingAndAChangeOnTheSameLineWaitsForTheNextTrain() {
        TimetableRouter router = router();
        // S1 ends at 나 (31090); S2 leaves at 31300. Later than L2 (30600), so it is the latest way to reach 다 by 31390.
        TimedRoute route = latest(router, 1, 3, 31390);
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("S1", "S2");
        assertThat(route.legs().get(1).walkSec()).isZero();
        assertThat(route.legs().get(1).waitSec()).isEqualTo(210);
        // Same line and direction without a transfer: one RIDE step that includes the wait.
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.path().steps()).singleElement().extracting(PathStep::stationCount, PathStep::durationSec).containsExactly(2, 390);
        assertStepsAddUp(route);
    }

    @Test
    void aLoopTrainIsRiddenThroughTheSeam() {
        TimetableRouter router = router();
        // R1: 마 32100 -> 가 -> 나 32290, crossing the loop's closing edge 마 -> 가.
        TimedRoute route = latest(router, 5, 2, 32290);
        assertThat(route.legs()).singleElement().satisfies(leg -> {
            assertThat(leg.trainCode()).isEqualTo("R1");
            assertThat(leg.stationCount()).isEqualTo(2);
        });
        // The second visit to 다 is part of the same train.
        assertThat(latest(router, 5, 3, 32390).legs()).singleElement().extracting(TimedLeg::trainCode).isEqualTo("R1");
        assertStepsAddUp(route);
    }

    @Test
    void branchJunctionNeedsTheChangeWalk() {
        TimetableRouter router = router();
        // 천호 -> 강동 -> 둔촌동 is one train through the junction (MAIN- <-> MACHEON).
        TimedRoute through = latest(router, 12, 13, 40190);
        assertThat(through.legs()).singleElement().extracting(TimedLeg::trainCode).isEqualTo("M1");
        assertThat(through.path().transferCount()).isZero();
        // 길동 -> 둔촌동 changes at 강동 (MAIN+ -> MACHEON): H1 reaches 강동 at 40090, M3 leaves at 40100 < 40090 + 16.
        assertThat(router.latestDepartures(13, at(THURSDAY, 40210), Set.of(11L), 0)).isEmpty();
        TimedRoute changed = latest(router, 11, 13, 40690);
        assertThat(changed.legs()).extracting(TimedLeg::trainCode).containsExactly("H1", "M2");
        assertThat(changed.legs().get(1).walkSec()).isEqualTo(16);
        assertThat(changed.legs().get(1).waitSec()).isEqualTo(40580 - 40090 - 16);
        assertThat(changed.path().transferCount()).isEqualTo(1);
        assertThat(changed.path().steps()).extracting(PathStep::type, PathStep::fromStationName, PathStep::toStationName)
                .containsExactly(tuple(StepType.RIDE, "길동", "강동"), tuple(StepType.TRANSFER, "강동", "강동"),
                        tuple(StepType.RIDE, "강동", "둔촌동"));
        assertThat(changed.path().steps().get(1).distanceM()).isEqualTo(19);
        assertStepsAddUp(changed);
    }

    @Test
    void afterMidnightCountsOnTheServiceDayBefore() {
        TimetableRouter router = router();
        // Friday 00:05 is Thursday's 86700: N1 leaves 가 at 24:01:40 (Friday 00:01:40).
        Instant friday0005 = at(THURSDAY.plusDays(1), 300);
        TimedRoute route = router.latestDepartures(2, friday0005, Set.of(1L), 0).get(1L);
        assertThat(route.legs().get(0).trainCode()).isEqualTo("N1");
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 86500)).isEqualTo(at(THURSDAY.plusDays(1), 100));
        // 01:30 is still Thursday's service day: N1 arrives at 00:03:10 and the member waits.
        assertThat(router.latestDepartures(2, at(THURSDAY.plusDays(1), 5400), Set.of(1L), 0).get(1L).legs().get(0).trainCode())
                .isEqualTo("N1");
        // 04:00 is Friday's service day before its first train: nobody can arrive.
        assertThat(router.latestDepartures(2, at(THURSDAY.plusDays(1), 14400), Set.of(1L), 0)).isEmpty();
    }

    @Test
    void forwardTakesTheNextTrainsAndReplaysTheBackwardRoute() {
        TimetableRouter router = router();
        TimetableRouteFinder.TimedTree tree = router.departingAt(1, at(THURSDAY, 30500));
        assertThat(tree.routeTo(3)).get().satisfies(route -> {
            assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("L2");
            assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 30810));
        });
        assertThat(tree.routeTo(4)).get().satisfies(route -> {
            assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("L2", "T2");
            assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 30990));
            assertStepsAddUp(route);
        });
        // Leaving at a backward route's leaveAt arrives by its deadline on the same first train.
        TimedRoute backward = latest(router, 11, 13, 40690);
        TimedRoute replay = router.departingAt(11, backward.leaveAt()).routeTo(13).orElseThrow();
        assertThat(replay.arriveAt()).isBeforeOrEqualTo(at(THURSDAY, 40690));
        assertThat(replay.legs()).extracting(TimedLeg::trainCode).containsExactly("H1", "M2");
        assertThat(replay.path().transferCount()).isEqualTo(1);
    }

    @Test
    void forwardAfterTheLastTrainStartsWithTheNextServiceDay() {
        TimetableRouter router = router();
        // Friday 00:30 is after Thursday's last departure (24:01:40): Friday's first trains, from 30000.
        TimedRoute route = router.departingAt(1, at(THURSDAY.plusDays(1), 1800)).routeTo(3).orElseThrow();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY.plusDays(1), 30000));
        assertThat(route.legs().get(0).trainCode()).isEqualTo("L1");
    }

    @Test
    void earliestArrivalsOfTheServiceDayUseItsFirstTrains() {
        TimetableRouter router = router();
        // From 가 on Thursday: L1 reaches 나 30090 and 다 30210, 라 via T1 30390, 마 via R1 (다 32000) 32090.
        Map<Long, Instant> earliest = router.earliestArrivalsOfServiceDay(1, at(THURSDAY, 40000));
        assertThat(earliest).containsOnlyKeys(2L, 3L, 4L, 5L);   // 5호선 is not reachable from 가
        assertThat(earliest.get(2L)).isEqualTo(at(THURSDAY, 30090));
        assertThat(earliest.get(3L)).isEqualTo(at(THURSDAY, 30210));
        assertThat(earliest.get(4L)).isEqualTo(at(THURSDAY, 30390));
        assertThat(earliest.get(5L)).isEqualTo(at(THURSDAY, 32090));
        // Whatever the time of day: Friday 01:00 is still Thursday's service day.
        assertThat(router.earliestArrivalsOfServiceDay(1, at(THURSDAY.plusDays(1), 3600))).isEqualTo(earliest);
        // A day without trains has none.
        assertThat(router.earliestArrivalsOfServiceDay(1, at(LocalDate.of(2026, 11, 14), 30000))).isEmpty();
    }

    @Test
    void stationsAreValidatedAndCoverageFollowsTheDayType() {
        TimetableRouter router = router();
        assertThatThrownBy(() -> router.requireStation(999)).isInstanceOf(BusinessException.class);
        assertThat(router.stationIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 10L, 11L, 12L, 13L);
        assertThat(router.covers(at(THURSDAY, 30000))).isTrue();
        // Only weekday trains exist here: Saturday has none.
        assertThat(router.covers(at(LocalDate.of(2026, 11, 14), 30000))).isFalse();
    }
}
