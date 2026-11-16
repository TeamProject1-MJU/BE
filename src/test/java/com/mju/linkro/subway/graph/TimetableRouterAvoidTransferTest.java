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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * departingAt with stations whose TRANSFER edges are not walked (#153). The graph of {@link TimetableRouterTest} plus a
 * 3호선 node at 마 (transfer 45 s) with a ride 마 -> 라, so 가 -> 라 can change lines at 나 or at 마.
 */
class TimetableRouterAvoidTransferTest {
    static final long NA = 2;
    static final long MA = 5;

    static TimetableRouter router() {
        List<Node> nodes = new ArrayList<>(TimetableRouterTest.DATA.nodes());
        nodes.add(new Node(35, MA, 3, "305", 4, "MAIN"));
        List<Edge> edges = new ArrayList<>(TimetableRouterTest.DATA.edges());
        edges.add(new Edge(30, 25, 35, StepType.TRANSFER, null, 45, 50));
        edges.add(new Edge(31, 35, 25, StepType.TRANSFER, null, 45, 50));
        edges.add(new Edge(32, 35, 34, StepType.RIDE, DirectionCode.UP, 90, 1000));
        SubwayGraphData data = new SubwayGraphData(TimetableRouterTest.DATA.lines(), TimetableRouterTest.DATA.stations(), nodes, edges);
        List<List<String>> trains = new ArrayList<>(TRAINS);
        // 2호선 L4 runs on to 마; 3호선 V1 마 -> 라.
        trains.add(train("2호선", "L4", "가", "마", "가:30650:30650", "나:30740:30750", "다:30840:30850", "마:30940:30940"));
        trains.add(train("3호선", "V1", "마", "라", "마:31000:31000", "라:31090:31090"));
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        trains.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        TrainTimetable timetable = TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), data);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDefaultBranchTransferSeconds(120);
        costs.setBranchTransfers(List.of(new SubwayGraphProperties.BranchTransfer("강동", "5", 16, 19)));
        return new TimetableRouter(SubwayGraph.of(data), timetable, new TimetableClock(new HolidayCalendar("")), costs, 0,
                Duration.ofMinutes(60));
    }

    static List<String> transferStations(TimedRoute route) {
        return route.path().steps().stream().filter(step -> step.type() == StepType.TRANSFER).map(PathStep::fromStationName).toList();
    }

    @Test
    void anEmptySetGivesExactlyTheRoutesOfTheTwoArgumentForm() {
        for (TimetableRouter router : List.of(TimetableRouterTest.router(), router())) {
            for (long origin : router.stationIds()) {
                for (int second : new int[]{29000, 30000, 30600, 30650, 40000, 86000}) {
                    Instant at = at(THURSDAY, second);
                    var plain = router.departingAt(origin, at);
                    var empty = router.departingAt(origin, at, Set.of());
                    assertThat(empty.reachableStationIds()).isEqualTo(plain.reachableStationIds());
                    for (long destination : router.stationIds()) {
                        assertThat(empty.routeTo(destination)).as("%d -> %d at %d", origin, destination, second)
                                .isEqualTo(plain.routeTo(destination));
                    }
                }
            }
        }
    }

    @Test
    void anAvoidedStationIsRiddenThroughButNotChangedAt() {
        TimetableRouter router = router();
        // 가 -> 라 at 30600: the change at 나 (T2 30900, 라 30990) is the earliest.
        TimedRoute best = router.departingAt(1, at(THURSDAY, 30600)).routeTo(4).orElseThrow();
        assertThat(transferStations(best)).containsExactly("나");
        assertThat(best.arriveAt()).isEqualTo(at(THURSDAY, 30990));

        // Without the change at 나: L4 rides through 나 to 마, walks 45 s and takes V1 (라 31090).
        TimedRoute avoiding = router.departingAt(1, at(THURSDAY, 30600), Set.of(NA)).routeTo(4).orElseThrow();
        assertThat(avoiding.legs()).extracting(TimedLeg::trainCode).containsExactly("L4", "V1");
        assertThat(transferStations(avoiding)).containsExactly("마");
        assertThat(avoiding.legs().get(1).walkSec()).isEqualTo(45);
        assertThat(avoiding.arriveAt()).isEqualTo(at(THURSDAY, 31090));
        assertThat(avoiding.path().stops()).extracting(PathStop::stationName).contains("나");

        // Without both changes no 3호선 train can be reached.
        assertThat(router.departingAt(1, at(THURSDAY, 30600), Set.of(NA, MA)).routeTo(4)).isEmpty();
        // Unknown station ids block nothing.
        assertThat(router.departingAt(1, at(THURSDAY, 30600), Set.of(999L)).routeTo(4)).contains(best);
    }

    @Test
    void routesWithoutAChangeAtTheStationAreNotAffected() {
        TimetableRouter router = router();
        // 가 -> 다 on 2호선 only.
        assertThat(router.departingAt(1, at(THURSDAY, 30600), Set.of(NA)).routeTo(3))
                .isEqualTo(router.departingAt(1, at(THURSDAY, 30600)).routeTo(3));
    }
}
