package com.mju.linkro.subway.congestion.load;

import com.mju.linkro.subway.congestion.load.CongestionLoadPlan.Node;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CongestionLoadPlanTest {
    /**
     * Nodes shaped like the seed: 2호선 용답/신설동 are SEONGSU and 도림천/까치산 SINJEONG nodes (UP/DOWN only), the junctions
     * 성수/신도림 are MAIN with UP/DOWN/INNER/OUTER, 6호선 역촌/구산 are on the one-way loop (LOOP only).
     */
    private static final Map<String, Node> NODES = Map.ofEntries(
            Map.entry(CongestionLoadPlan.key("1", "서울"), new Node(1, "MAIN", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("2", "시청"), new Node(11, "MAIN", Set.of("INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "뚝섬"), new Node(19, "MAIN", Set.of("INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "성수"), new Node(20, "MAIN", Set.of("UP", "DOWN", "INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "건대입구"), new Node(21, "MAIN", Set.of("INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "신도림"), new Node(36, "MAIN", Set.of("UP", "DOWN", "INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "신촌"), new Node(50, "MAIN", Set.of("INNER", "OUTER"))),
            Map.entry(CongestionLoadPlan.key("2", "용답"), new Node(54, "SEONGSU", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("2", "신설동"), new Node(57, "SEONGSU", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("2", "도림천"), new Node(58, "SINJEONG", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("2", "까치산"), new Node(61, "SINJEONG", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("4", "총신대입구"), new Node(110, "MAIN", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("5", "강동"), new Node(160, "MAIN", Set.of("UP", "DOWN"))),
            Map.entry(CongestionLoadPlan.key("6", "역촌"), new Node(212, "EUNGAM_LOOP", Set.of("LOOP"))),
            Map.entry(CongestionLoadPlan.key("6", "구산"), new Node(216, "EUNGAM_LOOP", Set.of("LOOP"))),
            Map.entry(CongestionLoadPlan.key("6", "응암"), new Node(178, "MAIN", Set.of("UP", "DOWN", "LOOP"))),
            Map.entry(CongestionLoadPlan.key("7", "총신대입구"), new Node(240, "MAIN", Set.of("UP", "DOWN"))));

    private static String direction(CongestionLoadPlan plan, long node, String dayType) {
        return plan.rows.stream().filter(row -> row.stationLineId() == node && row.dayType().equals(dayType))
                .map(CongestionLoadPlan.Row::direction).sorted().reduce((a, b) -> a + "," + b).orElse("-");
    }

    @Test
    void sampleIsCountedAndKeptAsGiven() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        assertThat(plan.read).isEqualTo(24);
        assertThat(plan.otherSlot).isZero();
        assertThat(plan.unmatched).containsExactly(Map.entry("3호선 없는역", 1));
        assertThat(plan.directionMismatch).containsExactly(Map.entry("1호선 INNER", 1), Map.entry("6호선 UP", 1));
        assertThat(plan.mapped).containsExactly(Map.entry("DOWN->LOOP", 1), Map.entry("INNER/OUTER->UP/DOWN", 6));
        // The 05:30 row (서울역 DOWN) is loaded like any other slot.
        assertThat(plan.rows).hasSize(21).allSatisfy(row -> assertThat(row.source()).isEqualTo("PREDICTED"));
        assertThat(plan.rows).anySatisfy(row -> assertThat(row.slotStart()).isEqualTo(LocalTime.of(5, 30)));
        var first = plan.rows.get(0);
        assertThat(first.pct()).isEqualTo(new BigDecimal("34.95326485813263"));
        assertThat(first.pct().toPlainString()).isEqualTo("34.95326485813263");
        assertThat(first.level()).isEqualTo("COMFORTABLE");
        assertThat(first.modelVersion()).isEqualTo(CongestionLoadFixture.MODEL);
    }

    @Test
    void aliasParenthesesAndMidnightRowsAreMatchedWithoutConversion() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        // 7호선 이수 -> 총신대입구(이수); its 00:00 row keeps SATURDAY as given.
        assertThat(plan.rows).anySatisfy(row -> {
            assertThat(row.stationLineId()).isEqualTo(240);
            assertThat(row.slotStart()).isEqualTo(LocalTime.MIDNIGHT);
            assertThat(row.dayType()).isEqualTo("SATURDAY");
            assertThat(row.level()).isEqualTo("RELAXED");
        });
        // 신촌(지하) -> 신촌, 강동(하남검단산) -> 강동 (DOWN) next to 강동 (UP).
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 50).singleElement()
                .satisfies(row -> assertThat(row.slotStart()).isEqualTo(LocalTime.of(23, 30)));
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 160).extracting(CongestionLoadPlan.Row::direction)
                .containsExactlyInAnyOrder("UP", "DOWN");
        assertThat(plan.rows).anySatisfy(row -> assertThat(row.slotStart()).isEqualTo(LocalTime.of(0, 30)));
    }

    @Test
    void loopOnlyNodesReadDownAsLoopAndRejectUp() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        // 역촌 (loop only): AI DOWN is stored as LOOP.
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 212).singleElement()
                .satisfies(row -> assertThat(row.direction()).isEqualTo("LOOP"));
        // 응암 is a main-line node (UP/DOWN/LOOP edges), so its DOWN stays DOWN and is not counted as mapped.
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 178).singleElement()
                .satisfies(row -> assertThat(row.direction()).isEqualTo("DOWN"));
        // 구산 (loop only): AI UP is not mapped and counts as a mismatch.
        assertThat(plan.rows).noneMatch(row -> row.stationLineId() == 216);
        assertThat(plan.directionMismatch).containsEntry("6호선 UP", 1);
        assertThat(plan.mapped).containsEntry("DOWN->LOOP", 1);
    }

    @Test
    void branchNodesMapInnerOuterByTheTable() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        // SEONGSU: INNER -> UP, OUTER -> DOWN. The terminus 신설동 has INNER only, which goes towards 성수 (UP).
        assertThat(direction(plan, 54, "WEEKDAY")).isEqualTo("DOWN,UP");
        assertThat(direction(plan, 57, "WEEKDAY")).isEqualTo("UP");
        // SINJEONG: INNER -> DOWN, OUTER -> UP. The terminus 까치산 has OUTER only, which goes towards 신도림 (UP).
        assertThat(direction(plan, 58, "WEEKDAY")).isEqualTo("DOWN,UP");
        assertThat(direction(plan, 61, "WEEKDAY")).isEqualTo("UP");
        // The junctions are MAIN nodes: INNER/OUTER stay as given.
        assertThat(direction(plan, 20, "WEEKDAY")).isEqualTo("INNER");
        assertThat(direction(plan, 36, "WEEKDAY")).isEqualTo("OUTER");
        // INNER on a non-branch UP/DOWN node is not converted and counts as a mismatch.
        assertThat(direction(plan, 1, "WEEKDAY")).isEqualTo("DOWN,UP");
        assertThat(plan.directionMismatch).containsEntry("1호선 INNER", 1);
        assertThat(plan.mapped).containsEntry("INNER/OUTER->UP/DOWN", 6);
    }

    @Test
    void theTableDecidesTheMappingNotTheCode() throws Exception {
        assertThat(DirectionMappings.load().reports()).containsExactly("DOWN->LOOP", "INNER/OUTER->UP/DOWN");
        // Same file shape with every branch direction flipped.
        DirectionMappings flipped = DirectionMappings.parse(List.of(
                "match,from,to,report",
                "directions=LOOP,DOWN,LOOP,DOWN->LOOP",
                "branch=SEONGSU,INNER,DOWN,INNER/OUTER->UP/DOWN",
                "branch=SEONGSU,OUTER,UP,INNER/OUTER->UP/DOWN",
                "branch=SINJEONG,INNER,UP,INNER/OUTER->UP/DOWN",
                "branch=SINJEONG,OUTER,DOWN,INNER/OUTER->UP/DOWN"));
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES,
                CongestionLoadFixture.MODEL, flipped);

        assertThat(direction(plan, 57, "WEEKDAY")).isEqualTo("DOWN");
        assertThat(direction(plan, 61, "WEEKDAY")).isEqualTo("DOWN");
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 54 && row.pct().equals(new BigDecimal("40")))
                .singleElement().satisfies(row -> assertThat(row.direction()).isEqualTo("DOWN"));

        // Without rules nothing is converted: branch INNER/OUTER and loop DOWN become mismatches.
        CongestionLoadPlan none = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES,
                CongestionLoadFixture.MODEL, DirectionMappings.parse(List.of("match,from,to,report")));
        assertThat(none.mapped).isEmpty();
        assertThat(none.directionMismatch).containsExactly(Map.entry("1호선 INNER", 1), Map.entry("2호선 INNER", 3),
                Map.entry("2호선 OUTER", 3), Map.entry("6호선 DOWN", 1), Map.entry("6호선 UP", 1));
    }

    // ---- ESTIMATED (성수 OUTER) ----

    private static Map<String, String> aiRow(String station, String direction, String slot, String pct) throws Exception {
        Map<String, String> row = new HashMap<>(CongestionCsv.read(CongestionLoadFixture.sampleCsv()).get(0));
        row.putAll(Map.of("line_code", "2", "station_name", station, "direction", direction, "day_type", "WEEKDAY", "slot_start", slot,
                "congestion_pct", pct, "congestion_level", CongestionLevels.of(new BigDecimal(pct)).code()));
        return row;
    }

    private static CongestionLoadPlan plan(List<Map<String, String>> rows) {
        return CongestionLoadPlan.of(rows, NODES, CongestionLoadFixture.MODEL);
    }

    @Test
    void seongsuOuterIsTheMeanOfItsNeighbours() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        // 건대입구 34.999999999998 and 뚝섬 34.999999999999 at WEEKDAY 08:00: just under 35, so COMFORTABLE.
        assertThat(plan.estimated).singleElement().satisfies(row -> {
            assertThat(row.stationLineId()).isEqualTo(20);
            assertThat(row.direction()).isEqualTo("OUTER");
            assertThat(row.dayType()).isEqualTo("WEEKDAY");
            assertThat(row.slotStart()).isEqualTo(LocalTime.of(8, 0));
            assertThat(row.pct().toPlainString()).isEqualTo("34.9999999999985");
            assertThat(row.level()).isEqualTo("COMFORTABLE");
            assertThat(row.label()).isEqualTo("쾌적");
            assertThat(row.source()).isEqualTo("ESTIMATED");
            assertThat(row.modelVersion()).isNull();
        });
        // 117 cells (3 day types x 39 slots); 건대입구 09:00 alone does not make a row.
        assertThat(plan.estimationSkipped).isEqualTo(116);
        assertThat(plan.rows).noneMatch(row -> row.stationLineId() == 20 && row.direction().equals("OUTER"));
    }

    @Test
    void meansAreExactAtLevelBoundaries() throws Exception {
        CongestionLoadPlan plan = plan(List.of(
                aiRow("건대입구", "OUTER", "07:00", "34.99"), aiRow("뚝섬", "OUTER", "07:00", "35.01"),
                aiRow("건대입구", "OUTER", "07:30", "49.999999999999"), aiRow("뚝섬", "OUTER", "07:30", "49.999999999998"),
                aiRow("건대입구", "OUTER", "08:00", "99.999999999999"), aiRow("뚝섬", "OUTER", "08:00", "100.000000000001"),
                aiRow("건대입구", "OUTER", "08:30", "0.1"), aiRow("뚝섬", "OUTER", "08:30", "0.2")));

        assertThat(plan.estimated).extracting(row -> row.slotStart() + " " + row.pct().toPlainString() + " " + row.level())
                .containsExactly("07:00 35.00 RELAXED", "07:30 49.9999999999985 RELAXED", "08:00 100.000000000000 CROWDED",
                        "08:30 0.15 COMFORTABLE");
        assertThat(plan.estimationSkipped).isEqualTo(117 - 4);
    }

    @Test
    void oneMissingNeighbourMakesNoRow() throws Exception {
        CongestionLoadPlan plan = plan(List.of(aiRow("건대입구", "OUTER", "08:00", "40"), aiRow("뚝섬", "OUTER", "09:00", "40"),
                aiRow("뚝섬", "INNER", "08:00", "40")));
        assertThat(plan.estimated).isEmpty();
        assertThat(plan.estimationSkipped).isEqualTo(117);
    }

    @Test
    void noEstimateWhenTheFileHasTheTarget() throws Exception {
        CongestionLoadPlan plan = plan(List.of(aiRow("건대입구", "OUTER", "08:00", "40"), aiRow("뚝섬", "OUTER", "08:00", "42"),
                aiRow("성수", "OUTER", "06:00", "30")));
        assertThat(plan.estimated).isEmpty();
        assertThat(plan.estimationSkipped).isZero();
        assertThat(plan.rows).filteredOn(row -> row.stationLineId() == 20).singleElement()
                .satisfies(row -> assertThat(row.source()).isEqualTo("PREDICTED"));
    }

    @Test
    void theEstimationTableDecidesTheTargets() throws Exception {
        assertThat(Estimations.load().rules()).containsExactly(new Estimations.Rule("2", "성수", "OUTER", "건대입구", "뚝섬"));
        List<Map<String, String>> rows = List.of(aiRow("건대입구", "OUTER", "08:00", "40"), aiRow("뚝섬", "OUTER", "08:00", "42"));

        CongestionLoadPlan none = CongestionLoadPlan.of(rows, NODES, CongestionLoadFixture.MODEL, DirectionMappings.load(),
                Estimations.parse(List.of("line_code,station,direction,previous,next")));
        assertThat(none.estimated).isEmpty();

        // A rule for 성수 INNER (not in the real table) from the same neighbours.
        CongestionLoadPlan inner = CongestionLoadPlan.of(List.of(aiRow("건대입구", "INNER", "08:00", "40"), aiRow("뚝섬", "INNER", "08:00", "42")),
                NODES, CongestionLoadFixture.MODEL, DirectionMappings.load(),
                Estimations.parse(List.of("line_code,station,direction,previous,next", "2,성수,INNER,뚝섬,건대입구")));
        assertThat(inner.estimated).singleElement().satisfies(row -> {
            assertThat(row.direction()).isEqualTo("INNER");
            assertThat(row.pct()).isEqualByComparingTo("41");
        });

        assertThatThrownBy(() -> Estimations.parse(List.of("line_code,station,direction,previous,next", "2,성수,OUTER,a,b", "2,성수,OUTER,c,d")))
                .hasMessageContaining("two rules");
        assertThatThrownBy(() -> CongestionLoadPlan.of(rows, NODES, CongestionLoadFixture.MODEL, DirectionMappings.load(),
                Estimations.parse(List.of("line_code,station,direction,previous,next", "2,없는역,OUTER,건대입구,뚝섬"))))
                .hasMessageContaining("unknown station");
    }

    @Test
    void computedLevelsFollowCkCongLevel() {
        assertThat(List.of("0", "34.999999999999", "35", "49.999999999999", "50", "99.999999999999", "100", "149.999999999999", "150", "180"))
                .extracting(pct -> CongestionLevels.of(new BigDecimal(pct)))
                .extracting(CongestionLevels.Level::code)
                .containsExactly("COMFORTABLE", "COMFORTABLE", "RELAXED", "RELAXED", "NORMAL", "NORMAL", "CROWDED", "CROWDED",
                        "VERY_CROWDED", "VERY_CROWDED");
        assertThat(CongestionLevels.of(new BigDecimal("150")).label()).isEqualTo("매우 혼잡");
    }

    @Test
    void badMappingFilesAreRejected() {
        assertThatThrownBy(() -> DirectionMappings.parse(List.of("match,from,to,report", "line=2,INNER,UP,x")))
                .hasMessageContaining("branch=... or directions=...");
        assertThatThrownBy(() -> DirectionMappings.parse(List.of("match,from,to,report",
                "branch=SEONGSU,INNER,UP,x", "branch=SEONGSU,INNER,DOWN,x"))).hasMessageContaining("two rules");
    }

    @Test
    void stationNamesNormalizeTheSameOnBothSides() {
        assertThat(StationNames.normalize(" 서울역 ")).isEqualTo("서울");
        assertThat(StationNames.normalize("서울역")).isEqualTo(StationNames.normalize("서울역"));
        assertThat(StationNames.normalize("신촌(지하)")).isEqualTo("신촌");
        assertThat(StationNames.normalize("자양(뚝섬한강공원)")).isEqualTo("자양");
        assertThat(StationNames.normalize("이수")).isEqualTo(StationNames.normalize("총신대입구(이수)")).isEqualTo("총신대입구");
        assertThat(StationNames.normalize("역촌")).isEqualTo("역촌");
        assertThat(StationNames.normalize("역")).isEqualTo("역");
    }

    @Test
    void allowedSlotsAre39() {
        assertThat(CongestionLoadPlan.ALLOWED_SLOTS).hasSize(39)
                .contains(LocalTime.of(5, 30), LocalTime.of(6, 0), LocalTime.of(23, 30), LocalTime.MIDNIGHT, LocalTime.of(0, 30))
                .doesNotContain(LocalTime.of(5, 0), LocalTime.of(5, 15), LocalTime.of(1, 0), LocalTime.of(23, 45), LocalTime.of(6, 15));
    }

    @Test
    void slotsOutsideThe39AreExcludedAndCounted(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("predictions_for_import.csv");
        List<String> lines = Files.readAllLines(CongestionLoadFixture.sampleCsv());
        Files.write(csv, List.of(lines.get(0), lines.get(1).replace(",06:00,", ",01:00,")));
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(csv), NODES, CongestionLoadFixture.MODEL);
        assertThat(plan.otherSlot).isEqualTo(1);
        assertThat(plan.rows).isEmpty();
    }

    @Test
    void wrongModelVersionOrDuplicateKeysStopThePlan(@TempDir Path dir) throws Exception {
        List<Map<String, String>> rows = CongestionCsv.read(CongestionLoadFixture.sampleCsv());
        assertThatThrownBy(() -> CongestionLoadPlan.of(rows, NODES, "OTHER_MODEL")).hasMessageContaining("differs from manifest");

        Path csv = dir.resolve("predictions_for_import.csv");
        List<String> lines = Files.readAllLines(CongestionLoadFixture.sampleCsv());
        Files.write(csv, List.of(lines.get(0), lines.get(1), lines.get(1)));
        assertThatThrownBy(() -> CongestionLoadPlan.of(CongestionCsv.read(csv), NODES, CongestionLoadFixture.MODEL))
                .hasMessageContaining("same congestion key");
    }

    @Test
    void csvKeepsQuotedCommasAndStripsTheBom() throws Exception {
        List<Map<String, String>> rows = CongestionCsv.read(CongestionLoadFixture.sampleCsv());
        assertThat(rows.get(0)).containsKey("target_period");
        assertThat(rows.get(3).get("description")).isEqualTo("승객이 많아요, 서서 가요.");
        assertThat(CongestionCsv.parse("a,\"b \"\"c\"\"\"\r\n1,2")).containsExactly(List.of("a", "b \"c\""), List.of("1", "2"));
    }

    @Test
    void missingColumnsAreRejected(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("predictions_for_import.csv");
        Files.writeString(csv, "line_code,station_name\n1,서울역\n");
        assertThatThrownBy(() -> CongestionCsv.read(csv)).hasMessageContaining("missing columns");
    }

    @Test
    void shaMismatchStopsBeforeTouchingTheDatabase(@TempDir Path dir) throws Exception {
        Path csv = CongestionLoadFixture.sampleCsv();
        Path manifest = CongestionLoadFixture.manifest(dir, csv, "0".repeat(64));
        // No connection: the load must fail on the SHA check first.
        assertThatThrownBy(() -> new CongestionLoader(null).load(csv, manifest))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHA-256 mismatch");
    }
}
