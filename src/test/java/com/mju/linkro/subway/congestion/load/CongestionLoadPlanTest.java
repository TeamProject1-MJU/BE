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
    /** Nodes shaped like the seed: 2호선 용답 is a SEONGSU node (UP/DOWN only), 6호선 역촌 is on the one-way loop (LOOP only). */
    private static final Map<String, Node> NODES = Map.of(
            CongestionLoadPlan.key("1", "서울"), new Node(1, Set.of("UP", "DOWN")),
            CongestionLoadPlan.key("2", "시청"), new Node(11, Set.of("INNER", "OUTER")),
            CongestionLoadPlan.key("2", "신촌"), new Node(50, Set.of("INNER", "OUTER")),
            CongestionLoadPlan.key("2", "용답"), new Node(54, Set.of("UP", "DOWN")),
            CongestionLoadPlan.key("4", "총신대입구"), new Node(110, Set.of("UP", "DOWN")),
            CongestionLoadPlan.key("5", "강동"), new Node(160, Set.of("UP", "DOWN")),
            CongestionLoadPlan.key("6", "역촌"), new Node(212, Set.of("LOOP")),
            CongestionLoadPlan.key("7", "총신대입구"), new Node(240, Set.of("UP", "DOWN")));

    @Test
    void sampleIsCountedAndKeptAsGiven() throws Exception {
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(CongestionLoadFixture.sampleCsv()), NODES, CongestionLoadFixture.MODEL);

        assertThat(plan.read).isEqualTo(11);
        assertThat(plan.excludedSlot).isEqualTo(1);
        assertThat(plan.otherSlot).isZero();
        assertThat(plan.unmatched).containsExactly(Map.entry("3호선 없는역", 1));
        assertThat(plan.directionMismatch).containsExactly(Map.entry("2호선 INNER", 1), Map.entry("6호선 DOWN", 1));
        assertThat(plan.rows).hasSize(7);
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
    void allowedSlotsAre38() {
        assertThat(CongestionLoadPlan.ALLOWED_SLOTS).hasSize(38)
                .contains(LocalTime.of(6, 0), LocalTime.of(23, 30), LocalTime.MIDNIGHT, LocalTime.of(0, 30))
                .doesNotContain(LocalTime.of(5, 30), LocalTime.of(1, 0), LocalTime.of(23, 45));
    }

    @Test
    void otherSlotsAreCountedApartFromTheExcluded0530(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("predictions_for_import.csv");
        List<String> lines = Files.readAllLines(CongestionLoadFixture.sampleCsv());
        Files.write(csv, List.of(lines.get(0), lines.get(1).replace(",06:00,", ",01:00,")));
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(csv), NODES, CongestionLoadFixture.MODEL);
        assertThat(plan.otherSlot).isEqualTo(1);
        assertThat(plan.excludedSlot).isZero();
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
