package com.mju.linkro.subway.congestion.load;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V13 (unscaled pct, 39 slots) and the PREDICTED loader on the real V1~V13 seed. */
@Testcontainers
class CongestionLoadPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    /** Shared with the unit tests (src/test/resources); Gradle runs tests from the project directory. */
    private static final Path SAMPLE = Path.of("src/test/resources/congestion-load/predictions_for_import.csv");
    private static final String MODEL = "HGB_20261103_39T";

    private String url;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "cong_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    // ---- V13 ----

    @Test
    void pctIsStoredWithoutRoundingAndLevelBoundsStillHold() throws SQLException {
        migrate(null);
        long node = node("서울역", "1");
        insert(node, "UP", "06:00", "34.95326485813263", "COMFORTABLE");
        insert(node, "DOWN", "06:00", "34.999999999999", "COMFORTABLE");
        assertThat(query("select congestion_pct::text from congestion_stats where direction = 'UP'")).containsExactly("34.95326485813263");
        assertThat(query("select congestion_pct from congestion_stats where direction = 'UP'").get(0))
                .isEqualTo(new BigDecimal("34.95326485813263"));
        assertThat(query("select congestion_pct::text from congestion_stats where direction = 'DOWN'")).containsExactly("34.999999999999");
        assertThat(query("select numeric_precision, numeric_scale from information_schema.columns "
                + "where table_name = 'congestion_stats' and column_name = 'congestion_pct'")).containsExactly(null, null);
        // ck_cong_level keeps working on unscaled values.
        assertViolation(() -> insert(node, "UP", "07:00", "35", "COMFORTABLE"), "ck_cong_level");
        assertViolation(() -> insert(node, "UP", "07:00", "34.999999999999", "RELAXED"), "ck_cong_level");
    }

    @ParameterizedTest
    @ValueSource(strings = {"05:30", "06:00", "23:30", "00:00", "00:30"})
    void allowedSlotsInsert(String slot) throws SQLException {
        migrate(null);
        insert(node("서울역", "1"), "UP", slot, "10", "COMFORTABLE");
        assertThat(query("select count(*) from congestion_stats")).containsExactly(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"05:15", "05:00", "01:00", "23:45", "06:15"})
    void otherSlotsViolateCkCongSlot(String slot) throws SQLException {
        migrate(null);
        long node = node("서울역", "1");
        assertViolation(() -> insert(node, "UP", slot, "10", "COMFORTABLE"), "ck_cong_slot");
    }

    @Test
    void v12DataSurvivesV13() throws SQLException {
        migrate("12");
        insert(node("서울역", "1"), "UP", "06:00", "12.5", "COMFORTABLE");
        var flyway = flyway(null);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        flyway.validate();
        assertThat(query("select congestion_pct::text, congestion_level from congestion_stats")).containsExactly("12.5", "COMFORTABLE");
        assertThat(query("select conname from pg_constraint where conrelid = 'congestion_stats'::regclass and conname = 'ck_cong_slot'"))
                .containsExactly("ck_cong_slot");
    }

    // ---- loader ----

    @Test
    void sampleLoadsAndReportsEverySkip(@TempDir Path dir) throws Exception {
        migrate(null);
        CongestionLoader.Report report = load(SAMPLE, validManifest(dir, SAMPLE));

        assertThat(report.read()).isEqualTo(21);
        assertThat(report.otherSlot()).isZero();
        assertThat(report.unmatched()).containsExactly(Map.entry("3호선 없는역", 1));
        assertThat(report.directionMismatch()).containsExactly(Map.entry("1호선 INNER", 1), Map.entry("6호선 UP", 1));
        assertThat(report.mapped()).containsExactly(Map.entry("DOWN->LOOP", 1), Map.entry("INNER/OUTER->UP/DOWN", 6));
        assertThat(report.loaded()).isEqualTo(18);
        assertThat(report.inserted()).isEqualTo(18);
        assertThat(report.updated()).isZero();
        assertThat(report.format()).contains("excluded other slots: 0", "mapped DOWN->LOOP: 1", "mapped INNER/OUTER->UP/DOWN: 6", "loaded rows: 18 (new 18, updated 0)").doesNotContain("05:30");

        assertThat(query("select count(*) from congestion_stats where source = 'PREDICTED' and model_version = '" + MODEL + "'")).containsExactly(18L);
        assertThat(query("select congestion_pct::text from congestion_stats where congestion_pct < 35 order by congestion_pct"))
                .containsExactly("11.4550105331955", "34.95326485813263", "34.999999999999");
        // 7호선 이수 00:00 SATURDAY is stored as given on the merged station's 7호선 node.
        assertThat(query("select cs.day_type, cs.congestion_level, cs.congestion_level_label from congestion_stats cs "
                + "join station_lines sl on sl.id = cs.station_line_id join subway_lines l on l.id = sl.line_id "
                + "join stations s on s.id = sl.station_id where s.name = '총신대입구(이수)' and l.line_code = '7' and cs.slot_start = time '00:00'"))
                .containsExactly("SATURDAY", "RELAXED", "여유");
        assertThat(query("select count(*) from congestion_stats where slot_start = time '05:30'")).containsExactly(1L);
        // 6호선: loop station 역촌 DOWN is stored as LOOP; main-line 응암 DOWN stays DOWN; loop 구산 UP is not stored.
        assertThat(query("select s.name || ':' || cs.direction from congestion_stats cs join station_lines sl on sl.id = cs.station_line_id "
                + "join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id where l.line_code = '6' order by s.name"))
                .containsExactly("역촌:LOOP", "응암:DOWN");
        // 2호선 on the real seed: branch nodes get UP/DOWN by the table, the junctions keep INNER/OUTER.
        assertThat(query("select s.name || ':' || cs.direction from congestion_stats cs join station_lines sl on sl.id = cs.station_line_id "
                + "join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id where l.line_code = '2' "
                + "and s.name in ('용답', '신설동', '도림천', '까치산', '성수', '신도림') order by s.name collate \"C\", cs.direction"))
                .containsExactly("까치산:UP", "도림천:DOWN", "도림천:UP", "성수:INNER", "신도림:OUTER", "신설동:UP", "용답:DOWN", "용답:UP");
    }

    @Test
    void rerunIsIdempotent(@TempDir Path dir) throws Exception {
        migrate(null);
        Path manifest = validManifest(dir, SAMPLE);
        load(SAMPLE, manifest);
        var before = query("select station_line_id, direction, day_type, slot_start::text, congestion_pct::text, congestion_level "
                + "from congestion_stats order by station_line_id, direction, day_type, slot_start");

        CongestionLoader.Report again = load(SAMPLE, manifest);

        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isEqualTo(18);
        assertThat(query("select count(*) from congestion_stats")).containsExactly(18L);
        assertThat(query("select station_line_id, direction, day_type, slot_start::text, congestion_pct::text, congestion_level "
                + "from congestion_stats order by station_line_id, direction, day_type, slot_start")).isEqualTo(before);
    }

    @Test
    void anyBadRowRollsBackTheWholeLoad(@TempDir Path dir) throws Exception {
        migrate(null);
        Path csv = dir.resolve("predictions_for_import.csv");
        List<String> lines = new ArrayList<>(Files.readAllLines(SAMPLE));
        // pct 40 with COMFORTABLE breaks ck_cong_level in the DB; the earlier good rows must not stay.
        lines.add(lines.get(1).replace(",06:00,", ",09:00,").replace("34.95326485813263", "40"));
        Files.write(csv, lines);

        assertThatThrownBy(() -> load(csv, validManifest(dir, csv))).isInstanceOf(SQLException.class).hasMessageContaining("ck_cong_level");
        assertThat(query("select count(*) from congestion_stats")).containsExactly(0L);
    }

    @Test
    void shaMismatchWritesNothing(@TempDir Path dir) throws Exception {
        migrate(null);
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"model_version\":\"" + MODEL + "\",\"output_sha256\":{\"predictions_for_import.csv\":\"" + "a".repeat(64) + "\"}}");
        assertThatThrownBy(() -> load(SAMPLE, manifest)).hasMessageContaining("SHA-256 mismatch");
        assertThat(query("select count(*) from congestion_stats")).containsExactly(0L);
    }

    // ---- helpers ----

    private CongestionLoader.Report load(Path csv, Path manifest) throws Exception {
        try (Connection connection = connect()) {
            return new CongestionLoader(connection).load(csv, manifest);
        }
    }

    private static Path validManifest(Path dir, Path csv) throws Exception {
        Path manifest = dir.resolve("manifest.json");
        Files.writeString(manifest, "{\"model_version\":\"" + MODEL + "\",\"output_sha256\":{\"" + csv.getFileName() + "\":\""
                + CongestionLoader.sha256(csv) + "\"}}");
        return manifest;
    }

    private void insert(long node, String direction, String slot, String pct, String level) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement("""
                insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level,
                                             congestion_level_label, source, model_version)
                values (?, ?, 'WEEKDAY', ?::time, ?, ?, '라벨', 'PREDICTED', ?)
                """)) {
            statement.setLong(1, node);
            statement.setString(2, direction);
            statement.setString(3, slot);
            statement.setBigDecimal(4, new BigDecimal(pct));
            statement.setString(5, level);
            statement.setString(6, MODEL);
            statement.executeUpdate();
        }
    }

    private long node(String station, String line) throws SQLException {
        return ((Number) query("select sl.id from station_lines sl join stations s on s.id = sl.station_id "
                + "join subway_lines l on l.id = sl.line_id where s.name = '" + station + "' and l.line_code = '" + line + "'").get(0)).longValue();
    }

    private List<Object> query(String sql) throws SQLException {
        List<Object> values = new ArrayList<>();
        try (Connection connection = connect(); Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) values.add(rs.getObject(i));
            }
        }
        return values;
    }

    private static void assertViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String constraint) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SQLException.class, exception -> {
            assertThat(exception.getSQLState()).isEqualTo("23514");
            assertThat(exception.getMessage()).contains("\"" + constraint + "\"");
        });
    }

    private void migrate(String target) {
        flyway(target).migrate();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null) config.target(target);
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
