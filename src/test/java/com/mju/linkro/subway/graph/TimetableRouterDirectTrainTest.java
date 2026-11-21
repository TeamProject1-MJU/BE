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
 * The direct train of #183 leaves the origin once the search starts boarding, never earlier: when the first 5호선 edge
 * from the origin has no train left that service day, there is none (PR #184 review).
 *
 * <pre>
 * 5호선: 광나루(14) -> 천호(12) -> 강동(10) -> 둔촌동(13, 마천지선), 강동 -> 길동(11)
 * 2호선: 광나루(14) -> 천호(12); walk at 천호 5호선 <-> 2호선 60 s
 * D1 광나루 39000 -> 천호 39100/39110 -> 강동 39180/39190 -> 둔촌동 39300 (마천행, the only train 광나루 -> 천호)
 * L  광나루 40000 -> 천호 40200 (2호선)
 * K  천호 40300 -> 강동 40370 (강동행)      M  강동 40400 -> 둔촌동 40510 (강동-origin 마천행)
 * </pre>
 *
 * From 광나루 at 39900 the route is L, walk, K and M with a change of train at 강동; D1 left 광나루 before the request.
 */
class TimetableRouterDirectTrainTest {
    static final long GWANGNARU = 14;
    static final long DUNCHON = 13;

    static final SubwayGraphData GRAPH = new SubwayGraphData(
            List.of(new Line(2, "2", "2호선", "#00A84D", 2), new Line(5, "5", "5호선", "#996CAC", 5)),
            List.of(station(10, "강동"), station(11, "길동"), station(12, "천호"), station(13, "둔촌동"), station(14, "광나루")),
            List.of(new Node(54, 14, 5, "546", 46, "MAIN"), new Node(52, 12, 5, "547", 47, "MAIN"),
                    new Node(50, 10, 5, "548", 48, "MAIN"), new Node(51, 11, 5, "549", 49, "MAIN"),
                    new Node(53, 13, 5, "552", 1, "MACHEON"),
                    new Node(64, 14, 2, "214", 1, "MAIN"), new Node(62, 12, 2, "212", 2, "MAIN")),
            List.of(ride(5, 54, 52, DirectionCode.DOWN, 100), ride(6, 52, 54, DirectionCode.UP, 100),
                    ride(7, 52, 50, DirectionCode.DOWN, 70), ride(8, 50, 53, DirectionCode.DOWN, 110),
                    ride(9, 51, 50, DirectionCode.UP, 90), ride(10, 50, 51, DirectionCode.DOWN, 90),
                    ride(11, 53, 50, DirectionCode.UP, 110), ride(12, 50, 52, DirectionCode.UP, 70),
                    ride(31, 64, 62, DirectionCode.DOWN, 200),
                    new Edge(35, 52, 62, StepType.TRANSFER, null, 60, 80), new Edge(36, 62, 52, StepType.TRANSFER, null, 60, 80)));

    static final List<List<String>> DIRECT_TRAINS = List.of(
            train("5호선", "D1", "방화", "마천", "광나루:39000:39000", "천호:39100:39110", "강동:39180:39190", "둔촌동:39300:39300"),
            train("2호선", "L", "광나루", "천호", "광나루:40000:40000", "천호:40200:40200"),
            train("5호선", "K", "방화", "강동", "천호:40300:40300", "강동:40370:40370"),
            train("5호선", "M", "강동", "마천", "강동:40400:40400", "둔촌동:40510:40510"));

    static TimetableRouter router() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        DIRECT_TRAINS.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
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
    void noDirectTrainLeftKeepsTheChangeInsteadOfAnEarlierTrain() {
        TimedRoute route = router().departingAt(GWANGNARU, at(THURSDAY, 39900)).routeTo(DUNCHON).orElseThrow();
        // Not D1, which left 광나루 at 39000, before the request.
        assertThat(route.leaveAt()).isAfterOrEqualTo(at(THURSDAY, 39900));
        assertThat(route.legs()).extracting(TimedLeg::trainCode).containsExactly("L", "K", "M");
        assertThat(route.boardAt()).isEqualTo(at(THURSDAY, 40000));
        assertThat(route.arriveAt()).isEqualTo(at(THURSDAY, 40510));
        assertThat(route.path().transferCount()).isEqualTo(2);
        assertStepsAddUp(route);
    }
}
