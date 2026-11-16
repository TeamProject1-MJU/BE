package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TimetableProperties.TransferOverride;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static com.mju.linkro.subway.graph.TimetableRouterTest.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Transfer overrides of the timetable routes (#151) on the graph of {@link TimetableRouterTest}, plus 3호선 nodes at
 * 다 (no transfer) and 마 (transfer 45 s, 50 m) and a 3호선 train U1 라 -> 나.
 *
 * <pre>
 * 나 2호선 <-> 3호선: edges 20 / 21, seed 60 s, 80 m
 * 마 2호선 <-> 3호선: edges 30 / 31, seed 45 s, 50 m (never overridden here)
 * </pre>
 */
class TimetableRouterTransferOverrideTest {
    static final SubwayGraphData DATA = extended();

    static SubwayGraphData extended() {
        List<Node> nodes = new ArrayList<>(TimetableRouterTest.DATA.nodes());
        nodes.add(new Node(33, 3, 3, "303", 3, "MAIN"));
        nodes.add(new Node(35, 5, 3, "305", 4, "MAIN"));
        List<Edge> edges = new ArrayList<>(TimetableRouterTest.DATA.edges());
        edges.add(new Edge(30, 25, 35, StepType.TRANSFER, null, 45, 50));
        edges.add(new Edge(31, 35, 25, StepType.TRANSFER, null, 45, 50));
        return new SubwayGraphData(TimetableRouterTest.DATA.lines(), TimetableRouterTest.DATA.stations(), nodes, edges);
    }

    static TimetableRouter router(TransferOverride... overrides) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        List<List<String>> trains = new ArrayList<>(TRAINS);
        // 3호선 the other way: 라 30500 -> 나 30590.
        trains.add(train("3호선", "U1", "라", "나", "라:30500:30500", "나:30590:30590"));
        trains.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
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
                Duration.ofMinutes(60), List.of(overrides));
    }

    static TransferOverride transfer(String station, String fromLine, String toLine, int seconds) {
        return new TransferOverride(station, fromLine, toLine, seconds);
    }

    @Test
    void anOverrideReplacesBothDirectionsAndKeepsTheDistance() {
        TimetableRouter router = router(transfer("나", "3", "2", 240));
        assertThat(router.edgeSeconds(20)).isEqualTo(240);
        assertThat(router.edgeSeconds(21)).isEqualTo(240);
        assertThat(router.edgeMeters(20)).isEqualTo(80);
        assertThat(router.edgeMeters(21)).isEqualTo(80);
        // A pair not listed keeps the seed seconds.
        assertThat(router.edgeSeconds(30)).isEqualTo(45);
        assertThat(router.edgeSeconds(31)).isEqualTo(45);
        // Without overrides (the old constructor) every transfer is the seed.
        TimetableRouter seed = router();
        assertThat(seed.edgeSeconds(20)).isEqualTo(60);
        assertThat(seed.edgeSeconds(21)).isEqualTo(60);
    }

    @Test
    void theForwardSearchWalksTheOverrideSeconds() {
        // 가 -> 라 leaving 30600: L2 reaches 나 at 30690, T2 leaves 나 at 30900.
        TimedRoute seed = router().departingAt(1, at(THURSDAY, 30600)).routeTo(4).orElseThrow();
        assertThat(seed.legs()).extracting(TimedLeg::trainCode).containsExactly("L2", "T2");
        assertThat(seed.legs().get(1).walkSec()).isEqualTo(60);

        // 210 s still makes T2 (30690 + 210 = 30900), 211 s does not and no later 3호선 train runs.
        TimedRoute exact = router(transfer("나", "2", "3", 210)).departingAt(1, at(THURSDAY, 30600)).routeTo(4).orElseThrow();
        assertThat(exact.legs()).extracting(TimedLeg::trainCode).containsExactly("L2", "T2");
        assertThat(exact.legs().get(1).walkSec()).isEqualTo(210);
        assertThat(exact.legs().get(1).waitSec()).isZero();
        Optional<TimedRoute> missed = router(transfer("나", "2", "3", 211)).departingAt(1, at(THURSDAY, 30600)).routeTo(4);
        assertThat(missed).isEmpty();
    }

    @Test
    void theOtherDirectionUsesTheSameOverride() {
        // 라 -> 다 on U1 (나 30590): with 60 s L2 (30720) is caught, with 240 s (30830) only S2 (31300).
        TimedRoute seed = router().departingAt(4, at(THURSDAY, 30500)).routeTo(3).orElseThrow();
        assertThat(seed.legs()).extracting(TimedLeg::trainCode).containsExactly("U1", "L2");
        TimedRoute longer = router(transfer("나", "2", "3", 240)).departingAt(4, at(THURSDAY, 30500)).routeTo(3).orElseThrow();
        assertThat(longer.legs()).extracting(TimedLeg::trainCode).containsExactly("U1", "S2");
        assertThat(longer.legs().get(1).walkSec()).isEqualTo(240);
        assertThat(longer.legs().get(1).waitSec()).isEqualTo(31300 - 30590 - 240);
    }

    @Test
    void theBackwardSearchWalksTheOverrideSeconds() {
        // 가 -> 라 by 30990 (T2 leaves 나 at 30900): 60 s takes L2 (나 30690); 240 s needs 나 by 30660, so L1 (30090).
        TimedRoute seed = router().latestDepartures(4, at(THURSDAY, 30990), Set.of(1L), 0).get(1L);
        assertThat(seed.legs()).extracting(TimedLeg::trainCode).containsExactly("L2", "T2");
        TimedRoute longer = router(transfer("나", "2", "3", 240)).latestDepartures(4, at(THURSDAY, 30990), Set.of(1L), 0).get(1L);
        assertThat(longer.legs()).extracting(TimedLeg::trainCode).containsExactly("L1", "T2");
        assertThat(longer.legs().get(1).walkSec()).isEqualTo(240);
        assertThat(longer.legs().get(1).waitSec()).isEqualTo(30900 - 30090 - 240);
        // The TRANSFER step lasts from leaving L1 to T2 and keeps the seed distance.
        PathStep transfer = longer.path().steps().get(1);
        assertThat(transfer.type()).isEqualTo(StepType.TRANSFER);
        assertThat(transfer.durationSec()).isEqualTo(30900 - 30090);
        assertThat(transfer.distanceM()).isEqualTo(80);
    }

    @Test
    void anUnknownStationFailsWithItsEntry() {
        assertThatThrownBy(() -> router(transfer("나", "2", "3", 120), transfer("없는역", "2", "3", 120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer-overrides[1]").hasMessageContaining("없는역 2<->3 120 s")
                .hasMessageContaining("no station named 없는역");
    }

    @Test
    void aLineNotStoppingAtTheStationFails() {
        assertThatThrownBy(() -> router(transfer("나", "2", "5", 120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer-overrides[0]").hasMessageContaining("line 5 does not stop there");
    }

    @Test
    void aPairWithoutTransferEdgesFails() {
        // 다 has 2호선 and 3호선 nodes but no TRANSFER edge between them.
        assertThatThrownBy(() -> router(transfer("다", "2", "3", 120)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer-overrides[0] (다 2<->3 120 s)").hasMessageContaining("no TRANSFER edges both ways");
    }

    @Test
    void aRepeatedPairFailsInEitherLineOrder() {
        assertThatThrownBy(() -> router(transfer("나", "2", "3", 120), transfer("나", "3", "2", 180)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer-overrides[1] (나 3<->2 180 s)").hasMessageContaining("the same pair as")
                .hasMessageContaining("transfer-overrides[0]");
    }

    @Test
    void negativeSecondsFail() {
        assertThatThrownBy(() -> router(transfer("나", "2", "3", -1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer-overrides[0] (나 2<->3 -1 s)").hasMessageContaining("must not be negative");
    }

    @Test
    void missingFieldsAndTheSameLineFail() {
        assertThatThrownBy(() -> router(transfer("나", null, "3", 120)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("are required");
        assertThatThrownBy(() -> router(transfer("나", "2", "2", 120)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("the two lines are the same");
    }
}
