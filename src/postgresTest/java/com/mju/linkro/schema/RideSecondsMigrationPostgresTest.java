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
 * V15 (#96) sets travel_seconds of 20 RIDE edges to their timetable median. Only those rows change, each to the
 * expected value, and a seed that differs from V11 makes it refuse.
 */
@Testcontainers
class RideSecondsMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String EDGES = "select c.id, l.line_code || ' ' || fs.name || '->' || ts.name, c.travel_seconds, c.distance_m, "
            + "c.connection_type, coalesce(c.direction, '-') from station_connections c "
            + "join station_lines f on f.id = c.from_station_line_id join stations fs on fs.id = f.station_id "
            + "join station_lines t on t.id = c.to_station_line_id join stations ts on ts.id = t.station_id "
            + "join subway_lines l on l.id = f.line_id order by c.id";

    /** line from->to: V11 seconds -> V15 seconds. */
    private static final Map<String, int[]> EXPECTED = Map.ofEntries(
            Map.entry("1 동대문->동묘앞", new int[]{60, 90}), Map.entry("1 동묘앞->동대문", new int[]{60, 90}),
            Map.entry("4 길음->미아사거리", new int[]{90, 120}), Map.entry("4 미아사거리->길음", new int[]{90, 120}),
            Map.entry("4 남태령->사당", new int[]{90, 120}), Map.entry("4 사당->남태령", new int[]{90, 120}),
            Map.entry("4 노원->창동", new int[]{120, 90}), Map.entry("4 창동->노원", new int[]{120, 90}),
            Map.entry("4 불암산->상계", new int[]{90, 150}), Map.entry("4 상계->불암산", new int[]{90, 150}),
            Map.entry("4 신용산->이촌", new int[]{90, 120}), Map.entry("4 이촌->신용산", new int[]{90, 120}),
            Map.entry("4 쌍문->창동", new int[]{90, 120}), Map.entry("4 창동->쌍문", new int[]{90, 120}),
            Map.entry("7 보라매->신풍", new int[]{140, 80}), Map.entry("7 신풍->보라매", new int[]{140, 80}),
            Map.entry("7 상도->장승배기", new int[]{150, 90}), Map.entry("7 장승배기->상도", new int[]{150, 90}),
            Map.entry("8 암사->암사역사공원", new int[]{90, 120}), Map.entry("8 암사역사공원->암사", new int[]{90, 180}));

    private String url;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "v15_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void onlyTheTwentyEdgesChangeToTheTimetableMedian() throws SQLException {
        flyway("14").migrate();
        Map<Long, List<Object>> before = byId(rows(EDGES));
        var latest = flyway("15");
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        Map<Long, List<Object>> after = byId(rows(EDGES));
        assertThat(after.keySet()).isEqualTo(before.keySet());
        List<Long> changed = before.keySet().stream().filter(id -> !before.get(id).equals(after.get(id))).toList();
        assertThat(changed).hasSize(20).allSatisfy(id -> {
            String name = (String) before.get(id).get(1);
            assertThat(EXPECTED).as(name).containsKey(name);
            assertThat(before.get(id).get(2)).as(name).isEqualTo(EXPECTED.get(name)[0]);
            assertThat(after.get(id).get(2)).as(name).isEqualTo(EXPECTED.get(name)[1]);
            // Only travel_seconds: distance, type and direction stay.
            assertThat(after.get(id).subList(3, 6)).isEqualTo(before.get(id).subList(3, 6));
        });
    }

    @Test
    void aSeedThatIsNotV11Refuses() throws SQLException {
        flyway("14").migrate();
        execute("update station_connections c set travel_seconds = 75 from station_lines f, stations s "
                + "where c.from_station_line_id = f.id and f.station_id = s.id and s.name = '보라매' and c.connection_type = 'RIDE' "
                + "and c.travel_seconds = 140");
        List<List<Object>> before = rows(EDGES);
        assertThatThrownBy(() -> flyway("15").migrate()).isInstanceOf(FlywayException.class)
                .hasMessageContaining("V15 expects one RIDE edge 7호선 보라매 -> 신풍 with travel_seconds 140, found 0");
        assertThat(count("select count(*) from flyway_schema_history where version = '15' and success")).isZero();
        // Rolled back: no edge changed, the other 19 targets included.
        assertThat(rows(EDGES)).isEqualTo(before);
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

    private List<List<Object>> rows(String sql) throws SQLException {
        List<List<Object>> values = new ArrayList<>();
        try (var connection = connect(); var statement = connection.prepareStatement(sql); var result = statement.executeQuery()) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) row.add(result.getObject(i));
                values.add(row);
            }
        }
        return values;
    }

    private long count(String sql) throws SQLException {
        return ((Number) rows(sql).get(0).get(0)).longValue();
    }
}
