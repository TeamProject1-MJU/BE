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

/**
 * The guard of #181 on its own (#183): riding through 강동 must not cost a transfer. Since #183 the change at 강동 counts
 * as a transfer, so the guard keeps a route only when the search without riding through finds one that needs fewer
 * transfers by another way (#107), and the narrow Z of #183 has no direct train from the origin to give way to (the
 * destination is off 5호선).
 *
 * <pre>
 * 5호선: 천호(12) -> 강동(10) -> 둔촌동(13, 마천지선), 강동 -> 길동(11)
 * 2호선: 천호(12) -> 둔촌동(13) -> 가(1); walks 천호 and 둔촌동 5호선 <-> 2호선 60 s
 * H  천호 40000 -> 강동 40070 (하남검단산행)      M  강동 40100 -> 둔촌동 40210 (강동-origin 마천행)
 * M' 천호 40200 -> 강동 40270/40280 -> 둔촌동 40390 (마천행, through)
 * L  천호 40050 -> 둔촌동 40350/40360 -> 가 40480   L' 둔촌동 40500 -> 가 40620
 * </pre>
 *
 * From 천호 at 39900 the forward search reaches 둔촌동 2호선 first by H, M and the walk, so it keeps L from there
 * (H, M, walk, L: two transfers, 40480). Riding through takes M' instead of H and M, which misses L: M', walk, L' (one
 * transfer, 40620), and searching back from 40620 leaves no later on fewer transfers. Without riding through, searching
 * back from 40480 finds L from 천호 (no transfer, 40480). The guard keeps L.
 */
class TimetableRouterGuardTest {
    static final long GA = 1;
    static final long CHEONHO = 12;

    static final SubwayGraphData GRAPH = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(5, "5", "5호선", "#996CAC", 5)),
            List.of(station(1, "가"), station(10, "강동"), station(11, "길동"), station(12, "천호"), station(13, "둔촌동")),
            List.of(new Node(50, 10, 5, "548", 48, "MAIN"), new Node(51, 11, 5, "549", 49, "MAIN"),
                    new Node(52, 12, 5, "547", 47, "MAIN"), new Node(53, 13, 5, "552", 1, "MACHEON"),
                    new Node(62, 12, 2, "201", 1, "MAIN"), new Node(63, 13, 2, "202", 2, "MAIN"),
                    new Node(61, 1, 2, "203", 3, "MAIN")),
            List.of(ride(7, 52, 50, DirectionCode.DOWN, 70), ride(8, 50, 53, DirectionCode.DOWN, 110),
                    ride(9, 51, 50, DirectionCode.UP, 90), ride(10, 50, 51, DirectionCode.DOWN, 90),
                    ride(11, 53, 50, DirectionCode.UP, 110), ride(12, 50, 52, DirectionCode.UP, 70),
                    ride(31, 62, 63, DirectionCode.DOWN, 300), ride(32, 63, 61, DirectionCode.DOWN, 120),
                    new Edge(33, 53, 63, StepType.TRANSFER, null, 60, 80), new Edge(34, 63, 53, StepType.TRANSFER, null, 60, 80),
                    new Edge(35, 52, 62, StepType.TRANSFER, null, 60, 80), new Edge(36, 62, 52, StepType.TRANSFER, null, 60, 80)));

    static final List<List<String>> GUARD_TRAINS = List.of(
            train("5호선", "H", "방화", "하남검단산", "천호:40000:40000", "강동:40070:40080", "길동:40170:40170"),
            train("5호선", "M", "강동", "마천", "강동:40100:40100", "둔촌동:40210:40210"),
            train("5호선", "M'", "방화", "마천", "천호:40200:40200", "강동:40270:40280", "둔촌동:40390:40390"),
            train("2호선", "L", "천호", "가", "천호:40050:40050", "둔촌동:40350:40360", "가:40480:40480"),
            train("2호선", "L'", "둔촌동", "가", "둔촌동:40500:40500", "가:40620:40620"));

    static TimetableRouter router() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        GUARD_TRAINS.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        TrainTimetable timetable = TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), GRAPH);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setBranchTransfers(List.of(new SubwayGraphProperties.BranchTransfer("강동", "5", 16, 19)));
        return new TimetableRouter(SubwayGraph.of(GRAPH), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                Duration.ofMinutes(60));
    }

    @Test
    void ridingThroughThatCostsATransferGivesWayToTheRouteWithFewer() {
        TimedRoute route = router().departingAt(CHEONHO, at(THURSDAY, 39900)).routeTo(GA).orElseThrow();
        // Not M', walk, L' (one transfer, 40620): riding through missed L.
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("L");
        assertThat(route.path().transferCount()).isZero();
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40050));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40480));
        assertStepsAddUp(route);
    }
}
