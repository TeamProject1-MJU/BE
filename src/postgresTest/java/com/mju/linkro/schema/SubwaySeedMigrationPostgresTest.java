package com.mju.linkro.schema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V11 subway seed data checked with SQL on disposable PostgreSQL, with no application .env or shared DB. */
@Testcontainers
class SubwaySeedMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String RIDE_DISTANCES = "select c.distance_m, sa.name as from_name, sb.name as to_name, "
        + "2 * 6371000 * asin(sqrt(power(sin(radians(sb.latitude - sa.latitude) / 2), 2) "
        + "+ cos(radians(sa.latitude)) * cos(radians(sb.latitude)) * power(sin(radians(sb.longitude - sa.longitude) / 2), 2))) as haversine_m "
        + "from station_connections c "
        + "join station_lines a on a.id = c.from_station_line_id join stations sa on sa.id = a.station_id "
        + "join station_lines b on b.id = c.to_station_line_id join stations sb on sb.id = b.station_id "
        + "where c.connection_type = 'RIDE'";

    private String url;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "seed_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void v10DatabaseAppliesSeedWithExpectedCounts() throws SQLException {
        flyway("10").migrate();
        assertThat(count("select count(*) from stations")).isZero();
        var history = rows("select version, checksum from flyway_schema_history order by installed_rank");

        var latest = flyway("11");
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(rows("select version, checksum from flyway_schema_history where version::int <= 10 order by installed_rank"))
            .isEqualTo(history);

        assertThat(count("select count(*) from subway_lines")).isEqualTo(8);
        assertThat(count("select count(*) from stations")).isEqualTo(240);
        assertThat(count("select count(*) from station_lines")).isEqualTo(277);
        assertThat(count("select count(*) from station_connections where connection_type = 'RIDE'")).isEqualTo(536);
        assertThat(count("select count(*) from station_connections where connection_type = 'TRANSFER'")).isEqualTo(78);
        assertThat(count("select count(*) from congestion_stats")).isZero();
        assertThat(rows("select line_code, name, color, sort_order from subway_lines order by sort_order"))
            .containsExactly(List.of("1", "1호선", "#0052A4", 1), List.of("2", "2호선", "#00A84D", 2),
                List.of("3", "3호선", "#EF7C1C", 3), List.of("4", "4호선", "#00A5DE", 4), List.of("5", "5호선", "#996CAC", 5),
                List.of("6", "6호선", "#CD7C2F", 6), List.of("7", "7호선", "#747F00", 7), List.of("8", "8호선", "#E6186C", 8));
    }

    @Test
    void everyNodeHasRideEdgeAndEveryNonLoopRideHasReverse() throws SQLException {
        seed();
        assertThat(rows("select sl.id from station_lines sl where not exists (select 1 from station_connections c "
            + "where c.connection_type = 'RIDE' and (c.from_station_line_id = sl.id or c.to_station_line_id = sl.id))")).isEmpty();
        assertThat(rows("select c.id from station_connections c where c.connection_type = 'RIDE' and c.direction <> 'LOOP' "
            + "and not exists (select 1 from station_connections r where r.connection_type = 'RIDE' "
            + "and r.from_station_line_id = c.to_station_line_id and r.to_station_line_id = c.from_station_line_id "
            + "and r.direction = case c.direction when 'INNER' then 'OUTER' when 'OUTER' then 'INNER' when 'UP' then 'DOWN' else 'UP' end)"))
            .isEmpty();
        // INNER/OUTER only on line 2 MAIN, LOOP only on the line 6 Eungam loop.
        assertThat(rows("select distinct l.line_code, sl.branch_code, c.direction from station_connections c "
            + "join station_lines sl on sl.id = c.from_station_line_id join subway_lines l on l.id = sl.line_id "
            + "where c.direction in ('INNER','OUTER','LOOP') order by 1, 3, 2"))
            .containsExactly(List.of("2", "MAIN", "INNER"), List.of("2", "MAIN", "OUTER"), List.of("6", "EUNGAM_LOOP", "LOOP"),
                List.of("6", "MAIN", "LOOP"));
    }

    @Test
    void line2MainIsLoopClosedAtCityHallAndEungamLoopClosesAtEungam() throws SQLException {
        seed();
        assertThat(cycle("2", "MAIN", "INNER", "시청")).containsExactly(List.of(43L, "시청"));
        assertThat(cycle("2", "MAIN", "OUTER", "시청")).containsExactly(List.of(43L, "시청"));
        assertThat(rows("select count(*) from station_lines sl join subway_lines l on l.id = sl.line_id "
            + "where l.line_code = '2' and sl.branch_code = 'MAIN'")).containsExactly(List.of(43L));
        assertThat(edge("2", "충정로", "시청")).containsExactly(List.of("INNER"));
        assertThat(edge("2", "시청", "충정로")).containsExactly(List.of("OUTER"));

        assertThat(count("select count(*) from station_connections where direction = 'LOOP'")).isEqualTo(6);
        assertThat(cycle("6", null, "LOOP", "응암")).containsExactly(List.of(6L, "응암"));
        assertThat(rows("select s.name, sl.branch_code, sl.seq from station_lines sl join stations s on s.id = sl.station_id "
            + "join subway_lines l on l.id = sl.line_id where l.line_code = '6' and (sl.branch_code = 'EUNGAM_LOOP' or s.name = '응암') "
            + "order by sl.branch_code, sl.seq"))
            .containsExactly(List.of("역촌", "EUNGAM_LOOP", 1), List.of("불광", "EUNGAM_LOOP", 2), List.of("독바위", "EUNGAM_LOOP", 3),
                List.of("연신내", "EUNGAM_LOOP", 4), List.of("구산", "EUNGAM_LOOP", 5), List.of("응암", "MAIN", 1));
    }

    @Test
    void seqStartsAtOneWithoutGapsPerLineAndBranch() throws SQLException {
        seed();
        assertThat(rows("select l.line_code, sl.branch_code from station_lines sl join subway_lines l on l.id = sl.line_id "
            + "group by 1, 2 having min(sl.seq) <> 1 or max(sl.seq) <> count(*) or count(distinct sl.seq) <> count(*)")).isEmpty();
        assertThat(rows("select l.line_code, sl.branch_code, count(*) from station_lines sl join subway_lines l on l.id = sl.line_id "
            + "where sl.branch_code <> 'MAIN' group by 1, 2 order by 1, 2"))
            .containsExactly(List.of("2", "SEONGSU", 4L), List.of("2", "SINJEONG", 4L), List.of("5", "MACHEON", 7L),
                List.of("6", "EUNGAM_LOOP", 5L));
        assertThat(branch("2", "SEONGSU")).containsExactly(List.of("용답"), List.of("신답"), List.of("용두"), List.of("신설동"));
        assertThat(branch("2", "SINJEONG")).containsExactly(List.of("도림천"), List.of("양천구청"), List.of("신정네거리"), List.of("까치산"));
        assertThat(branch("5", "MACHEON")).containsExactly(List.of("둔촌동"), List.of("올림픽공원"), List.of("방이"),
            List.of("오금"), List.of("개롱"), List.of("거여"), List.of("마천"));
        // Junction stations keep only their MAIN node.
        assertThat(rows("select s.name, l.line_code, sl.branch_code from station_lines sl join stations s on s.id = sl.station_id "
            + "join subway_lines l on l.id = sl.line_id where (s.name, l.line_code) in (('성수','2'),('신도림','2'),('강동','5'),('응암','6')) "
            + "order by 2, 1"))
            .containsExactly(List.of("성수", "2", "MAIN"), List.of("신도림", "2", "MAIN"), List.of("강동", "5", "MAIN"),
                List.of("응암", "6", "MAIN"));
        assertThat(rows("select l.line_code, sl.seq from station_lines sl join stations s on s.id = sl.station_id "
            + "join subway_lines l on l.id = sl.line_id where s.name = '시청' order by 1"))
            .containsExactly(List.of("1", 2), List.of("2", 1));
    }

    @Test
    void rideAndTransferEdgesFormOneConnectedGraph() throws SQLException {
        seed();
        assertThat(count("with recursive reached(id) as (select min(id) from station_lines union "
            + "select c.to_station_line_id from station_connections c join reached r on c.from_station_line_id = r.id) "
            + "select count(*) from reached")).isEqualTo(277);
    }

    @Test
    void everyLinePairOfAStationHasTransfersInBothDirections() throws SQLException {
        seed();
        assertThat(rows("select a.id, b.id from station_lines a join station_lines b on a.station_id = b.station_id and a.id <> b.id "
            + "where not exists (select 1 from station_connections c where c.connection_type = 'TRANSFER' "
            + "and c.from_station_line_id = a.id and c.to_station_line_id = b.id)")).isEmpty();
        assertThat(count("select count(*) from station_connections c join station_lines a on a.id = c.from_station_line_id "
            + "join station_lines b on b.id = c.to_station_line_id where c.connection_type = 'TRANSFER' "
            + "and (a.station_id <> b.station_id or a.line_id = b.line_id or c.direction is not null)")).isZero();
        assertThat(transfer("2", "5", "까치산")).containsExactly(List.of(14, 17));
        assertThat(transfer("5", "2", "까치산")).containsExactly(List.of(14, 17));
        assertThat(rows("select l.line_code, sl.station_code from station_lines sl join stations s on s.id = sl.station_id "
            + "join subway_lines l on l.id = sl.line_id where s.name = '총신대입구(이수)' order by 1"))
            .containsExactly(List.of("4", "432"), List.of("7", "2738"));
        assertThat(count("select count(*) from station_connections c join station_lines a on a.id = c.from_station_line_id "
            + "join stations s on s.id = a.station_id where s.name = '총신대입구(이수)' and c.connection_type = 'TRANSFER'")).isEqualTo(2);
    }

    @Test
    void coordinatesAreDistinctPerLineAndConsistentWithRideDistances() throws SQLException {
        seed();
        assertThat(rows("select a.id, b.id from station_lines a join station_lines b on a.line_id = b.line_id and a.id < b.id "
            + "join stations sa on sa.id = a.station_id join stations sb on sb.id = b.station_id "
            + "where sa.latitude = sb.latitude and sa.longitude = sb.longitude")).isEmpty();
        assertThat(rows("select from_name, to_name, distance_m, round(haversine_m) from (" + RIDE_DISTANCES + ") d "
            + "where haversine_m < 0.45 * distance_m or haversine_m > 1.15 * distance_m + 300")).isEmpty();
    }

    @Test
    void coordinatesAreInSeoulBoundsAndAllStationsSupported() throws SQLException {
        seed();
        assertThat(rows("select name from stations where latitude not between 37.4 and 37.8 or longitude not between 126.7 and 127.3"))
            .isEmpty();
        assertThat(rows("select distinct is_supported from stations")).containsExactly(List.of(true));
        assertThat(rows("select count(*), count(distinct name) from stations")).containsExactly(List.of(240L, 240L));
        // Merged stations keep the coordinates of their lowest line.
        assertThat(rows("select name, latitude, longitude from stations where name in ('서울역', '총신대입구(이수)', '자양')"))
            .containsExactlyInAnyOrder(List.of("서울역", 37.55315, 126.972533), List.of("자양", 37.531558, 127.066714),
                List.of("총신대입구(이수)", 37.487521, 126.982309));
        assertThat(rows("select name from stations where name in ('서울', '뚝섬유원지', '총신대입구', '이수')")).isEmpty();
    }

    @Test
    void sixStationOverridesAreApplied() throws SQLException {
        seed();
        assertThat(rows("select s.name, l.line_code, s.latitude, s.longitude from stations s join station_lines sl on sl.station_id = s.id "
            + "join subway_lines l on l.id = sl.line_id where s.name in ('잠실새내','용답','신답','마곡','발산','암사역사공원') order by 2, 1"))
            .containsExactly(List.of("신답", "2", 37.57001, 127.04652), List.of("용답", "2", 37.56188, 127.0509),
                List.of("잠실새내", "2", 37.51139, 127.08611), List.of("마곡", "5", 37.560167, 126.825417),
                List.of("발산", "5", 37.55861, 126.83722), List.of("암사역사공원", "8", 37.55679, 127.13578));
        assertThat(rows("select sl.station_code, sl.seq, sl.branch_code from station_lines sl join stations s on s.id = sl.station_id "
            + "where s.name = '암사역사공원'")).containsExactly(List.of("2810", 1, "MAIN"));
        assertThat(edge("8", "암사역사공원", "암사")).containsExactly(List.of("DOWN"));
    }

    @Test
    void seedRespectsAllV10Constraints() throws SQLException {
        seed();
        // Only valid rows could be inserted; spot-check the values that V10 CHECKs guard.
        assertThat(count("select count(*) from station_connections where travel_seconds <= 0 or distance_m < 0")).isZero();
        assertThat(rows("select distinct color = upper(color) from subway_lines")).containsExactly(List.of(true));
    }

    @Test
    void seedStopsWhenSubwayTablesAreNotEmpty() throws SQLException {
        flyway("10").migrate();
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.execute("insert into stations(name, latitude, longitude) values ('시청', 37.5, 127.0)");
        }

        assertThatThrownBy(() -> flyway("11").migrate())
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("V11 seeds empty tables only");
        assertThat(count("select count(*) from stations")).isEqualTo(1);
        assertThat(count("select count(*) from subway_lines")).isZero();
        assertThat(rows("select version from flyway_schema_history where version = '11' and success")).isEmpty();
    }

    private void seed() throws SQLException {
        flyway("11").migrate();
    }

    private List<List<Object>> cycle(String line, String branch, String direction, String start) throws SQLException {
        // A null branch follows the direction across all branches of the line.
        return rows("with recursive edges as (select c.from_station_line_id f, c.to_station_line_id t from station_connections c "
            + "join station_lines sl on sl.id = c.from_station_line_id join subway_lines l on l.id = sl.line_id "
            + "where l.line_code = ? and c.direction = ? and sl.branch_code = coalesce(cast(? as varchar), sl.branch_code)), "
            + "start as (select sl.id from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id "
            + "where l.line_code = ? and s.name = ?), "
            + "walk(node, steps) as (select t, 1 from edges where f = (select id from start) "
            + "union all select e.t, w.steps + 1 from walk w join edges e on e.f = w.node "
            + "where w.node <> (select id from start) and w.steps < 100) "
            + "select w.steps::bigint, s.name from walk w join station_lines sl on sl.id = w.node join stations s on s.id = sl.station_id "
            + "where w.node = (select id from start)", line, direction, branch, line, start);
    }

    private List<List<Object>> edge(String line, String from, String to) throws SQLException {
        return rows("select c.direction from station_connections c "
            + "join station_lines a on a.id = c.from_station_line_id join stations sa on sa.id = a.station_id "
            + "join station_lines b on b.id = c.to_station_line_id join stations sb on sb.id = b.station_id "
            + "join subway_lines l on l.id = a.line_id where c.connection_type = 'RIDE' and l.line_code = ? "
            + "and sa.name = ? and sb.name = ?", line, from, to);
    }

    private List<List<Object>> transfer(String fromLine, String toLine, String station) throws SQLException {
        return rows("select c.travel_seconds, c.distance_m from station_connections c "
            + "join station_lines a on a.id = c.from_station_line_id join subway_lines la on la.id = a.line_id "
            + "join station_lines b on b.id = c.to_station_line_id join subway_lines lb on lb.id = b.line_id "
            + "join stations s on s.id = a.station_id where c.connection_type = 'TRANSFER' "
            + "and la.line_code = ? and lb.line_code = ? and s.name = ?", fromLine, toLine, station);
    }

    private List<List<Object>> branch(String line, String branchCode) throws SQLException {
        return rows("select s.name from station_lines sl join stations s on s.id = sl.station_id join subway_lines l on l.id = sl.line_id "
            + "where l.line_code = ? and sl.branch_code = ? order by sl.seq", line, branchCode);
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").cleanDisabled(true).target(target).load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private List<List<Object>> rows(String sql, Object... params) throws SQLException {
        var values = new ArrayList<List<Object>>();
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) { statement.setObject(i + 1, params[i]); }
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    var row = new ArrayList<Object>();
                    for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) { row.add(resultSet.getObject(i)); }
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
