package com.mju.linkro.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V14 (#89) swaps line 1 UP/DOWN to Seoul 상행/하행 and reverses line 1 MAIN seq. It cannot be undone, so the test
 * checks exactly which rows change and that nothing else does.
 */
@Testcontainers
class Line1DirectionMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String EDGES = "select c.id, l.line_code, c.connection_type, coalesce(c.direction, '-') from station_connections c "
            + "join station_lines f on f.id = c.from_station_line_id join subway_lines l on l.id = f.line_id order by c.id";
    private static final String NODES = "select sl.id, l.line_code, sl.branch_code, sl.seq from station_lines sl "
            + "join subway_lines l on l.id = sl.line_id order by sl.id";

    private String url;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "line1_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void onlyLine1RideDirectionsAndSeqChange() throws SQLException {
        flyway("13").migrate();
        Map<Long, List<Object>> edgesBefore = byId(rows(EDGES));
        Map<Long, List<Object>> nodesBefore = byId(rows(NODES));
        assertThat(next("종각", "UP")).containsExactly("시청");

        var latest = flyway("14");
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();

        Map<Long, List<Object>> edgesAfter = byId(rows(EDGES));
        Map<Long, List<Object>> nodesAfter = byId(rows(NODES));
        assertThat(edgesAfter.keySet()).isEqualTo(edgesBefore.keySet());
        assertThat(nodesAfter.keySet()).isEqualTo(nodesBefore.keySet());
        List<Long> changedEdges = edgesBefore.keySet().stream().filter(id -> !edgesBefore.get(id).equals(edgesAfter.get(id))).toList();
        List<Long> changedNodes = nodesBefore.keySet().stream().filter(id -> !nodesBefore.get(id).equals(nodesAfter.get(id))).toList();
        // 18 line 1 RIDE edges (9 UP + 9 DOWN), each swapped; every other line and every TRANSFER is untouched.
        assertThat(changedEdges).hasSize(18).allSatisfy(id -> {
            List<Object> before = edgesBefore.get(id);
            List<Object> after = edgesAfter.get(id);
            assertThat(before.get(1)).isEqualTo("1");
            assertThat(before.get(2)).isEqualTo("RIDE");
            assertThat(after.get(3)).isEqualTo("UP".equals(before.get(3)) ? "DOWN" : "UP");
        });
        // 10 line 1 MAIN nodes, all seq changed (no fixed point with 10 values).
        assertThat(changedNodes).hasSize(10).allSatisfy(id -> assertThat(nodesBefore.get(id).get(1)).isEqualTo("1"));
        assertThat(count("select count(*) from station_lines sl join subway_lines l on l.id = sl.line_id where l.line_code = '1'")).isEqualTo(10);
    }

    @Test
    void line1NowFollowsSeoul상행하행() throws SQLException {
        flyway("14").migrate();
        // Seoul at 종각: 상행 next is 종로3가 (towards 청량리), 하행 next is 시청 (towards 서울역).
        assertThat(next("종각", "UP")).containsExactly("종로3가");
        assertThat(next("종각", "DOWN")).containsExactly("시청");
        assertThat(rows("select s.name from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id "
                + "where l.line_code = '1' order by sl.seq").stream().map(row -> (String) row.get(0)).toList())
                .containsExactly("청량리", "제기동", "신설동", "동묘앞", "동대문", "종로5가", "종로3가", "종각", "시청", "서울역");
        // MAIN seq follows DOWN again: every DOWN edge goes from seq n to n + 1.
        assertThat(rows("select f.seq, t.seq from station_connections c join station_lines f on f.id = c.from_station_line_id "
                + "join station_lines t on t.id = c.to_station_line_id join subway_lines l on l.id = f.line_id "
                + "where l.line_code = '1' and c.direction = 'DOWN'")).hasSize(9)
                .allSatisfy(row -> assertThat(((Number) row.get(1)).intValue()).isEqualTo(((Number) row.get(0)).intValue() + 1));
        // Every RIDE still has its opposite edge.
        assertThat(count("select count(*) from station_connections c where c.connection_type = 'RIDE' and c.direction in ('UP', 'DOWN') "
                + "and not exists (select 1 from station_connections r where r.from_station_line_id = c.to_station_line_id "
                + "and r.to_station_line_id = c.from_station_line_id and r.direction = case c.direction when 'UP' then 'DOWN' else 'UP' end)"))
                .isZero();
    }

    @Test
    void congestionRowsAreNotTouched() throws SQLException {
        flyway("13").migrate();
        execute("insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level, "
                + "congestion_level_label, source, model_version) select sl.id, 'UP', 'WEEKDAY', '08:00', 38.4, 'RELAXED', '여유', "
                + "'PREDICTED', 'HGB_TEST' from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id "
                + "where s.name = '종각' and l.line_code = '1'");
        flyway("14").migrate();
        assertThat(rows("select direction, congestion_pct::text from congestion_stats")).containsExactly(List.of("UP", "38.4"));
    }

    /**
     * Weekday 08:00 at 종각 as loaded from the AI file: UP = 상선 = towards 청량리 (38.412), DOWN = 하선 = towards 서울역
     * (48.448). A lookup by the seed direction of 종각 -> 시청 (서울역 방면) read the 청량리 value before V14.
     */
    @Test
    void congestionLookedUpByTheSeedDirectionFollowsTheFix() throws SQLException {
        flyway("13").migrate();
        for (String[] row : new String[][] {{"UP", "38.412"}, {"DOWN", "48.448"}}) {
            execute("insert into congestion_stats(station_line_id, direction, day_type, slot_start, congestion_pct, congestion_level, "
                    + "congestion_level_label, source, model_version) select sl.id, '" + row[0] + "', 'WEEKDAY', '08:00', " + row[1]
                    + ", 'RELAXED', '여유', 'PREDICTED', 'HGB_TEST' from station_lines sl join stations s on s.id = sl.station_id "
                    + "join subway_lines l on l.id = sl.line_id where s.name = '종각' and l.line_code = '1'");
        }
        assertThat(pctTowards("시청")).isEqualTo("38.412");    // V13: 서울역 방면 read the 청량리 (상선) value
        assertThat(pctTowards("종로3가")).isEqualTo("48.448");

        flyway("14").migrate();

        assertThat(pctTowards("시청")).isEqualTo("48.448");    // 서울역 방면 = 하선
        assertThat(pctTowards("종로3가")).isEqualTo("38.412"); // 청량리 방면 = 상선
    }

    /** Congestion at 종각 (weekday 08:00) for the train leaving towards the given neighbour, keyed by the seed direction. */
    private String pctTowards(String neighbour) throws SQLException {
        return (String) rows("select cs.congestion_pct::text from station_connections c "
                + "join station_lines f on f.id = c.from_station_line_id join stations s on s.id = f.station_id "
                + "join station_lines g on g.id = c.to_station_line_id join stations t on t.id = g.station_id "
                + "join congestion_stats cs on cs.station_line_id = f.id and cs.direction = c.direction "
                + "and cs.day_type = 'WEEKDAY' and cs.slot_start = '08:00' "
                + "where c.connection_type = 'RIDE' and s.name = '종각' and t.name = ? "
                + "and f.line_id = (select id from subway_lines where line_code = '1')", neighbour).get(0).get(0);
    }

    @Test
    void aDatabaseThatIsNotTheV11SeedStopsTheMigration() throws SQLException {
        flyway("13").migrate();
        // Someone already swapped one edge by hand: V14 must refuse instead of swapping it back.
        execute("update station_connections c set direction = 'DOWN' from station_lines f, stations s "
                + "where c.from_station_line_id = f.id and f.station_id = s.id and s.name = '종각' and c.direction = 'UP' "
                + "and f.line_id = (select id from subway_lines where line_code = '1')");
        assertThatThrownBy(() -> flyway("14").migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("V14 expects the V11 line 1 seed");
        assertThat(count("select count(*) from flyway_schema_history where version = '14' and success")).isZero();
    }

    private List<String> next(String station, String direction) throws SQLException {
        return rows("select t.name from station_connections c join station_lines f on f.id = c.from_station_line_id "
                + "join stations s on s.id = f.station_id join station_lines g on g.id = c.to_station_line_id join stations t on t.id = g.station_id "
                + "join subway_lines l on l.id = f.line_id where c.connection_type = 'RIDE' and l.line_code = '1' and s.name = ? and c.direction = ?",
                station, direction).stream().map(row -> (String) row.get(0)).toList();
    }

    private static Map<Long, List<Object>> byId(List<List<Object>> rows) {
        Map<Long, List<Object>> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(((Number) row.get(0)).longValue(), row));
        return map;
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).target(target).load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql) throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private List<List<Object>> rows(String sql, Object... params) throws SQLException {
        List<List<Object>> values = new ArrayList<>();
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            try (var result = statement.executeQuery()) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= columns; i++) row.add(result.getObject(i));
                    values.add(row);
                }
            }
        }
        return values;
    }

    private long count(String sql) throws SQLException {
        return ((Number) rows(sql).get(0).get(0)).longValue();
    }
}
