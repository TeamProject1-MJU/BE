package com.mju.linkro.schema;

import com.github.f4b6a3.uuid.UuidCreator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V10 subway graph and congestion schema on disposable PostgreSQL, with no application .env or shared DB. */
@Testcontainers
class SubwaySchemaMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private String url;
    private long line1;
    private long line2;
    private long cityHallStation;
    private long cityHall1;
    private long cityHall2;
    private long euljiro2;
    private long chungjeongno2;
    private long seongsu2;
    private long yongdap2;
    private long sindorim2;
    private long dorimcheon2;
    private long congestion;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "subway_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void freshDatabaseMigratesToV10ValidatesAndRerunsWithoutChanges() throws SQLException {
        var flyway = flyway(null);
        // Count from resources so later migrations do not break this test.
        int pending = flyway.info().pending().length;
        assertThat(pending).isGreaterThanOrEqualTo(10);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(pending);
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        var history = rows("select version, checksum, success from flyway_schema_history order by installed_rank");
        assertThat(rows("select success from flyway_schema_history where version = '10'")).containsExactly(List.of(true));
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(rows("select version, checksum, success from flyway_schema_history order by installed_rank")).isEqualTo(history);
        assertThat(rows("select count(*) from pg_type where typtype = 'e'")).containsExactly(List.of(0L));
    }

    @Test
    void v9DatabaseUpgradesToV10AndKeepsExistingRowsAndChecksums() throws SQLException {
        flyway("9").migrate();
        UUID user = id();
        exec("insert into users(id,kakao_id,nickname) values (?,?,'user')", user, user.toString());
        exec("insert into refresh_tokens(id,user_id,family_id,token_hash,expires_at) values (?,?,?,?,now()+interval '1 day')",
            id(), user, id(), "a".repeat(64));
        exec("insert into stations(name,latitude,longitude) values ('시청',37.5657,126.9769)");
        exec("insert into meeting_rooms(id,room_code,name,created_by) values (?,'ROOM01','room',?)", id(), user);
        var before = new ArrayList<List<List<Object>>>();
        for (String table : List.of("users", "refresh_tokens", "stations", "meeting_rooms")) {
            before.add(rows("select * from " + table + " order by id"));
        }
        var history = rows("select version, checksum from flyway_schema_history order by installed_rank");

        // Stops at V10: the V11 seed would add stations and is covered by SubwaySeedMigrationPostgresTest.
        var latest = flyway("10");
        int pending = latest.info().pending().length;
        assertThat(pending).isEqualTo(1);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(pending);
        assertThat(latest.info().pending()).isEmpty();
        assertThat(rows("select success from flyway_schema_history where version = '10'")).containsExactly(List.of(true));
        latest.validate();

        var after = new ArrayList<List<List<Object>>>();
        for (String table : List.of("users", "refresh_tokens", "stations", "meeting_rooms")) {
            after.add(rows("select * from " + table + " order by id"));
        }
        assertThat(after).isEqualTo(before);
        assertThat(rows("select version, checksum from flyway_schema_history where version::int <= 9 order by installed_rank"))
            .isEqualTo(history);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        // The existing stations table is referenced, not recreated.
        assertThat(rows("select count(*) from stations")).containsExactly(List.of(1L));
    }

    @Test
    void columnsMatchErdTypesNullabilityAndDefaults() throws SQLException {
        flyway(null).migrate();
        assertThat(rows("select table_name, column_name, data_type, coalesce(character_maximum_length, numeric_precision), "
            + "numeric_scale, is_nullable, is_identity, column_default from information_schema.columns "
            + "where table_name in ('subway_lines','station_lines','station_connections','congestion_stats') "
            + "order by table_name, ordinal_position"))
            .containsExactly(
                row("congestion_stats", "id", "bigint", 64, 0, "NO", "YES", null),
                row("congestion_stats", "station_line_id", "bigint", 64, 0, "NO", "NO", null),
                row("congestion_stats", "direction", "character varying", 5, null, "NO", "NO", null),
                row("congestion_stats", "day_type", "character varying", 8, null, "NO", "NO", null),
                row("congestion_stats", "slot_start", "time without time zone", null, null, "NO", "NO", null),
                row("congestion_stats", "congestion_pct", "numeric", 5, 1, "NO", "NO", null),
                row("congestion_stats", "congestion_level", "character varying", 12, null, "NO", "NO", null),
                row("congestion_stats", "congestion_level_label", "character varying", 10, null, "NO", "NO", null),
                row("congestion_stats", "source", "character varying", 9, null, "NO", "NO", null),
                row("congestion_stats", "model_version", "character varying", 20, null, "YES", "NO", null),
                row("station_connections", "id", "bigint", 64, 0, "NO", "YES", null),
                row("station_connections", "from_station_line_id", "bigint", 64, 0, "NO", "NO", null),
                row("station_connections", "to_station_line_id", "bigint", 64, 0, "NO", "NO", null),
                row("station_connections", "connection_type", "character varying", 8, null, "NO", "NO", null),
                row("station_connections", "direction", "character varying", 5, null, "YES", "NO", null),
                row("station_connections", "travel_seconds", "integer", 32, 0, "NO", "NO", null),
                row("station_connections", "distance_m", "integer", 32, 0, "NO", "NO", null),
                row("station_lines", "id", "bigint", 64, 0, "NO", "YES", null),
                row("station_lines", "station_id", "bigint", 64, 0, "NO", "NO", null),
                row("station_lines", "line_id", "bigint", 64, 0, "NO", "NO", null),
                row("station_lines", "station_code", "character varying", 10, null, "NO", "NO", null),
                row("station_lines", "seq", "integer", 32, 0, "NO", "NO", null),
                row("station_lines", "branch_code", "character varying", 20, null, "NO", "NO", "'MAIN'::character varying"),
                row("subway_lines", "id", "bigint", 64, 0, "NO", "YES", null),
                row("subway_lines", "line_code", "character varying", 10, null, "NO", "NO", null),
                row("subway_lines", "name", "character varying", 20, null, "NO", "NO", null),
                row("subway_lines", "color", "character", 7, null, "NO", "NO", null),
                row("subway_lines", "sort_order", "integer", 32, 0, "NO", "NO", null));
    }

    @Test
    void allNamedConstraintsIndexesAndDeleteActionsExist() throws SQLException {
        flyway(null).migrate();
        assertThat(rows("select conname from pg_constraint where contype = 'c' and conrelid in "
            + "('subway_lines'::regclass,'station_lines'::regclass,'station_connections'::regclass,'congestion_stats'::regclass)"))
            .containsExactlyInAnyOrder(List.of("ck_lines_color"), List.of("ck_sl_seq"), List.of("ck_conn_not_self"),
                List.of("ck_conn_dir"), List.of("ck_conn_nonneg"), List.of("ck_conn_type"), List.of("ck_conn_direction_val"),
                List.of("ck_cong_pct"), List.of("ck_cong_slot"), List.of("ck_cong_model"), List.of("ck_cong_level"),
                List.of("ck_cong_direction"), List.of("ck_cong_day"), List.of("ck_cong_source"));
        assertThat(rows("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_cong_source'").get(0).get(0).toString())
            .contains("PREDICTED", "MOCK").doesNotContain("ACTUAL");
        assertThat(rows("select conname, pg_get_constraintdef(oid) from pg_constraint where contype = 'u' and conrelid in "
            + "('subway_lines'::regclass,'station_lines'::regclass,'station_connections'::regclass,'congestion_stats'::regclass)"))
            .containsExactlyInAnyOrder(
                List.of("uq_lines_code", "UNIQUE (line_code)"),
                List.of("uq_sl_station_line", "UNIQUE (station_id, line_id)"),
                List.of("uq_sl_line_code", "UNIQUE (line_id, station_code)"),
                List.of("uq_sl_line_branch_seq", "UNIQUE (line_id, branch_code, seq)"),
                List.of("uq_conn_from_to", "UNIQUE (from_station_line_id, to_station_line_id)"),
                List.of("uq_cong_slot_source", "UNIQUE (station_line_id, direction, day_type, slot_start, source)"));
        assertThat(rows("select indexdef from pg_indexes where indexname = 'idx_conn_to'").get(0).get(0).toString())
            .contains("station_connections", "(to_station_line_id)");
        // ERD has no delete action on these refs, so they keep the PostgreSQL default (NO ACTION).
        assertThat(rows("select conname, confrelid::regclass::text, confdeltype::text from pg_constraint where contype = 'f' and conrelid in "
            + "('station_lines'::regclass,'station_connections'::regclass,'congestion_stats'::regclass)"))
            .containsExactlyInAnyOrder(List.of("fk_sl_station", "stations", "a"), List.of("fk_sl_line", "subway_lines", "a"),
                List.of("fk_conn_from", "station_lines", "a"), List.of("fk_conn_to", "station_lines", "a"),
                List.of("fk_cong_station_line", "station_lines", "a"));
    }

    @Test
    void validLine2LoopBranchesTransfersAndCongestionRowsAreAccepted() throws SQLException {
        fixture();
        assertThat(rows("select line_code, branch_code, seq from station_lines sl join subway_lines l on l.id = sl.line_id "
            + "where sl.id in (?,?,?) order by sl.id", cityHall2, yongdap2, dorimcheon2))
            .containsExactly(List.of("2", "MAIN", 1), List.of("2", "SEONGSU", 1), List.of("2", "SINJEONG", 1));
        // Loop closure runs against seq: 충정로(43) -> 시청(1) is INNER, 시청 -> 충정로 is OUTER.
        assertThat(rows("select direction from station_connections where from_station_line_id = ? and to_station_line_id = ?",
            chungjeongno2, cityHall2)).containsExactly(List.of("INNER"));
        assertThat(rows("select direction from station_connections where from_station_line_id = ? and to_station_line_id = ?",
            cityHall2, chungjeongno2)).containsExactly(List.of("OUTER"));
        // Transfers are directional and may differ per direction.
        assertThat(rows("select travel_seconds, distance_m, direction is null from station_connections "
            + "where connection_type = 'TRANSFER' order by from_station_line_id"))
            .containsExactly(List.of(120, 150, true), List.of(90, 110, true));
        assertThat(rows("select count(*) from station_connections")).containsExactly(List.of(10L));
        // Same slot may hold both sources; the label is stored as loaded.
        assertThat(rows("select source, model_version, congestion_level_label from congestion_stats "
            + "where station_line_id = ? and slot_start = time '06:00' order by source", cityHall2))
            .containsExactly(row("MOCK", null, "쾌적"), row("PREDICTED", "v1.0", "쾌적"));
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'LOOP','SUNDAY',time '23:00',9999.9,'VERY_CROWDED','매우 혼잡','MOCK')", cityHall2);
        exec("update congestion_stats set congestion_level_label = '아무 값' where id = ?", congestion);
    }

    @ParameterizedTest
    @CsvSource({"0.0,COMFORTABLE", "34.9,COMFORTABLE", "35.0,RELAXED", "49.9,RELAXED", "50.0,NORMAL", "99.9,NORMAL",
        "100.0,CROWDED", "149.9,CROWDED", "150.0,VERY_CROWDED", "9999.9,VERY_CROWDED"})
    void levelBoundariesAreLowerInclusiveUpperExclusive(String pct, String level) throws SQLException {
        fixture();
        exec("update congestion_stats set congestion_pct = ?, congestion_level = ? where id = ?", new BigDecimal(pct), level, congestion);
        assertThat(rows("select congestion_pct, congestion_level from congestion_stats where id = ?", congestion))
            .containsExactly(List.of(new BigDecimal(pct), level));
    }

    @ParameterizedTest
    @CsvSource({"35.0,COMFORTABLE", "34.9,RELAXED", "50.0,RELAXED", "49.9,NORMAL", "100.0,NORMAL", "99.9,CROWDED",
        "150.0,CROWDED", "149.9,VERY_CROWDED", "50.0,BUSY", "50.0,normal"})
    void levelOutsideItsPctRangeIsRejected(String pct, String level) throws SQLException {
        fixture();
        var before = rows("select * from congestion_stats order by id");
        failure("update congestion_stats set congestion_pct = ?, congestion_level = ? where id = ?", "23514", "ck_cong_level",
            new BigDecimal(pct), level, congestion);
        assertThat(rows("select * from congestion_stats order by id")).isEqualTo(before);
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
            Arguments.of("update subway_lines set color = '#12345G'", "ck_lines_color"),
            Arguments.of("update subway_lines set color = '00A84D'", "ck_lines_color"),
            Arguments.of("update subway_lines set color = '#FFF'", "ck_lines_color"),
            Arguments.of("update station_lines set seq = 0", "ck_sl_seq"),
            Arguments.of("update station_lines set seq = -1", "ck_sl_seq"),
            Arguments.of("update station_connections set to_station_line_id = from_station_line_id", "ck_conn_not_self"),
            Arguments.of("update station_connections set direction = null where connection_type = 'RIDE'", "ck_conn_dir"),
            Arguments.of("update station_connections set direction = 'UP' where connection_type = 'TRANSFER'", "ck_conn_dir"),
            Arguments.of("update station_connections set travel_seconds = 0", "ck_conn_nonneg"),
            Arguments.of("update station_connections set travel_seconds = -1", "ck_conn_nonneg"),
            Arguments.of("update station_connections set distance_m = -1", "ck_conn_nonneg"),
            Arguments.of("update station_connections set direction = 'LEFT' where connection_type = 'RIDE'", "ck_conn_direction_val"),
            // An unknown type also fails ck_conn_dir; PostgreSQL reports CHECKs in name order, so ck_conn_dir comes first.
            Arguments.of("update station_connections set connection_type = 'WALK' where connection_type = 'TRANSFER'", "ck_conn_dir"),
            Arguments.of("update congestion_stats set congestion_pct = -0.1 where congestion_level = 'COMFORTABLE'", "ck_cong_pct"),
            Arguments.of("update congestion_stats set slot_start = time '05:30'", "ck_cong_slot"),
            Arguments.of("update congestion_stats set slot_start = time '23:30'", "ck_cong_slot"),
            Arguments.of("update congestion_stats set slot_start = time '06:15'", "ck_cong_slot"),
            Arguments.of("update congestion_stats set slot_start = time '05:59'", "ck_cong_slot"),
            Arguments.of("update congestion_stats set model_version = null where source = 'PREDICTED'", "ck_cong_model"),
            Arguments.of("update congestion_stats set model_version = 'v1.0' where source = 'MOCK'", "ck_cong_model"),
            Arguments.of("update congestion_stats set direction = 'LEFT'", "ck_cong_direction"),
            Arguments.of("update congestion_stats set day_type = 'HOLIDAY'", "ck_cong_day"),
            Arguments.of("update congestion_stats set source = 'ACTUAL' where source = 'MOCK'", "ck_cong_source")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void namedChecksRejectInvalidRowsAtomically(String sql, String constraint) throws SQLException {
        fixture();
        String table = sql.split(" ")[1];
        var before = rows("select * from " + table + " order by id");
        failure(sql, "23514", constraint);
        assertThat(rows("select * from " + table + " order by id")).isEqualTo(before);
    }

    @Test
    void connectionTypeCheckRejectsUnknownTypeOnItsOwn() throws SQLException {
        fixture();
        // ck_conn_dir is reported first for an unknown type, so check ck_conn_type with ck_conn_dir out of the way.
        exec("alter table station_connections drop constraint ck_conn_dir");
        failure("update station_connections set connection_type = 'WALK'", "23514", "ck_conn_type");
    }

    @Test
    void uniqueConstraintsRejectDuplicates() throws SQLException {
        fixture();
        failure("insert into subway_lines(line_code,name,color,sort_order) values ('2','2호선 중복','#00A84D',9)", "23505", "uq_lines_code");
        failure("insert into station_lines(station_id,line_id,station_code,seq) values (?,?,'299',99)", "23505", "uq_sl_station_line",
            cityHallStation, line2);
        long newStation = station("신설역");
        failure("insert into station_lines(station_id,line_id,station_code,seq) values (?,?,'201',99)", "23505", "uq_sl_line_code",
            newStation, line2);
        failure("insert into station_lines(station_id,line_id,station_code,seq) values (?,?,'299',1)", "23505", "uq_sl_line_branch_seq",
            newStation, line2);
        failure("insert into station_lines(station_id,line_id,station_code,seq,branch_code) values (?,?,'299',1,'SEONGSU')", "23505",
            "uq_sl_line_branch_seq", newStation, line2);
        // Same seq on another branch or another line is legal.
        stationLine(newStation, line1, "133", 1, "GYEONGBU");
        failure("insert into station_connections(from_station_line_id,to_station_line_id,connection_type,direction,travel_seconds,distance_m) "
            + "values (?,?,'RIDE','OUTER',60,800)", "23505", "uq_conn_from_to", cityHall2, euljiro2);
        failure("insert into station_connections(from_station_line_id,to_station_line_id,connection_type,travel_seconds,distance_m) "
            + "values (?,?,'TRANSFER',60,80)", "23505", "uq_conn_from_to", cityHall2, cityHall1);
        failure("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'INNER','WEEKDAY',time '06:00',80.0,'NORMAL','보통','MOCK')", "23505", "uq_cong_slot_source", cityHall2);
        // Changing any one of the five key columns makes the row distinct.
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'OUTER','WEEKDAY',time '06:00',80.0,'NORMAL','보통','MOCK')", cityHall2);
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'INNER','SATURDAY',time '06:00',80.0,'NORMAL','보통','MOCK')", cityHall2);
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'INNER','WEEKDAY',time '06:30',80.0,'NORMAL','보통','MOCK')", cityHall2);
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'INNER','WEEKDAY',time '06:00',80.0,'NORMAL','보통','MOCK')", euljiro2);
    }

    @Test
    void foreignKeysRejectMissingTargetsAndBlockDeletingReferencedRows() throws SQLException {
        fixture();
        failure("update station_lines set station_id = -1 where id = ?", "23503", "fk_sl_station", cityHall2);
        failure("update station_lines set line_id = -1 where id = ?", "23503", "fk_sl_line", cityHall2);
        failure("update station_connections set from_station_line_id = -1 where from_station_line_id = ? and to_station_line_id = ?",
            "23503", "fk_conn_from", cityHall2, euljiro2);
        failure("update station_connections set to_station_line_id = -1 where from_station_line_id = ? and to_station_line_id = ?",
            "23503", "fk_conn_to", cityHall2, euljiro2);
        failure("update congestion_stats set station_line_id = -1 where id = ?", "23503", "fk_cong_station_line", congestion);
        failure("delete from stations where id = ?", "23503", "fk_sl_station", cityHallStation);
        failure("delete from subway_lines where id = ?", "23503", "fk_sl_line", line2);
        failure("delete from station_lines where id = ?", "23503", null, euljiro2);
        long congestionOnly = stationLine(station("신설역"), line2, "299", 99, "MAIN");
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'UP','SUNDAY',time '12:00',40.0,'RELAXED','여유','MOCK')", congestionOnly);
        failure("delete from station_lines where id = ?", "23503", "fk_cong_station_line", congestionOnly);
    }

    @Test
    void notNullColumnsAndDefaultBranchAreEnforced() throws SQLException {
        fixture();
        assertThat(rows("select branch_code from station_lines where id = ?", cityHall1)).containsExactly(List.of("MAIN"));
        failure("update station_lines set branch_code = null", "23502", null);
        failure("update subway_lines set color = null", "23502", null);
        failure("update station_connections set distance_m = null", "23502", null);
        failure("update congestion_stats set congestion_level_label = null", "23502", null);
        failure("update subway_lines set line_code = repeat('a', 11)", "22001", null);
        failure("update congestion_stats set congestion_pct = 10000.0", "22003", null);
    }

    private void fixture() throws SQLException {
        // Schema-only fixture: V11 seed rows would collide with these lines and stations.
        flyway("10").migrate();
        line1 = id("insert into subway_lines(line_code,name,color,sort_order) values ('1','1호선','#0052A4',1) returning id");
        line2 = id("insert into subway_lines(line_code,name,color,sort_order) values ('2','2호선','#00a84d',2) returning id");
        cityHallStation = station("시청");
        cityHall1 = id("insert into station_lines(station_id,line_id,station_code,seq) values (?,?,'132',1) returning id",
            cityHallStation, line1);
        cityHall2 = stationLine(cityHallStation, line2, "201", 1, "MAIN");
        euljiro2 = stationLine(station("을지로입구"), line2, "202", 2, "MAIN");
        seongsu2 = stationLine(station("성수"), line2, "211", 11, "MAIN");
        sindorim2 = stationLine(station("신도림"), line2, "234", 34, "MAIN");
        chungjeongno2 = stationLine(station("충정로"), line2, "243", 43, "MAIN");
        // Branch stations restart seq at 1 under their own branch_code.
        yongdap2 = stationLine(station("용답"), line2, "211-1", 1, "SEONGSU");
        dorimcheon2 = stationLine(station("도림천"), line2, "234-1", 1, "SINJEONG");
        ride(cityHall2, euljiro2, "INNER");
        ride(euljiro2, cityHall2, "OUTER");
        ride(chungjeongno2, cityHall2, "INNER");
        ride(cityHall2, chungjeongno2, "OUTER");
        ride(seongsu2, yongdap2, "DOWN");
        ride(yongdap2, seongsu2, "UP");
        ride(sindorim2, dorimcheon2, "DOWN");
        ride(dorimcheon2, sindorim2, "UP");
        exec("insert into station_connections(from_station_line_id,to_station_line_id,connection_type,travel_seconds,distance_m) "
            + "values (?,?,'TRANSFER',120,150)", cityHall1, cityHall2);
        exec("insert into station_connections(from_station_line_id,to_station_line_id,connection_type,travel_seconds,distance_m) "
            + "values (?,?,'TRANSFER',90,110)", cityHall2, cityHall1);
        congestion = id("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,"
            + "congestion_level_label,source) values (?,'INNER','WEEKDAY',time '06:00',12.5,'COMFORTABLE','쾌적','MOCK') returning id", cityHall2);
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source,model_version) "
            + "values (?,'INNER','WEEKDAY',time '06:00',20.0,'COMFORTABLE','쾌적','PREDICTED','v1.0')", cityHall2);
        exec("insert into congestion_stats(station_line_id,direction,day_type,slot_start,congestion_pct,congestion_level,congestion_level_label,source) "
            + "values (?,'DOWN','SATURDAY',time '23:00',150.0,'VERY_CROWDED','매우 혼잡','MOCK')", dorimcheon2);
    }

    private long station(String name) throws SQLException {
        return id("insert into stations(name,latitude,longitude) values (?,37.5,127.0) returning id", name);
    }

    private long stationLine(long stationId, long lineId, String code, int seq, String branch) throws SQLException {
        return id("insert into station_lines(station_id,line_id,station_code,seq,branch_code) values (?,?,?,?,?) returning id",
            stationId, lineId, code, seq, branch);
    }

    private void ride(long from, long to, String direction) throws SQLException {
        exec("insert into station_connections(from_station_line_id,to_station_line_id,connection_type,direction,travel_seconds,distance_m) "
            + "values (?,?,'RIDE',?,90,1000)", from, to, direction);
    }

    private long id(String insertReturningId, Object... params) throws SQLException {
        return ((Number) rows(insertReturningId, params).get(0).get(0)).longValue();
    }

    private static UUID id() { return UuidCreator.getTimeOrderedEpoch(); }

    private static List<Object> row(Object... values) {
        return java.util.Arrays.asList(values);
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null) { config.target(target); }
        return config.load();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void exec(String sql, Object... params) throws SQLException {
        try (var connection = connect(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) { statement.setObject(i + 1, params[i]); }
            statement.executeUpdate();
        }
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

    private void failure(String sql, String state, String constraint, Object... params) {
        assertThatThrownBy(() -> exec(sql, params)).isInstanceOfSatisfying(SQLException.class, exception -> {
            assertThat(exception.getSQLState()).isEqualTo(state);
            if (constraint != null) { assertThat(exception.getMessage()).contains("\"" + constraint + "\""); }
        });
    }
}
