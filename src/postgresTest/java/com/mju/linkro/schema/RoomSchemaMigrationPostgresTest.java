package com.mju.linkro.schema;

import com.github.f4b6a3.uuid.UuidCreator;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real production migrations on disposable PostgreSQL, with no application .env or shared DB. */
@Testcontainers
class RoomSchemaMigrationPostgresTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private String url;
    private UUID owner;
    private UUID member;
    private UUID outsider;
    private UUID room;
    private UUID otherRoom;
    private UUID run;
    private UUID result;
    private UUID proposal;
    private UUID otherProposal;
    private long station;
    private long otherStation;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        String database = "room_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("create database " + database);
        }
        url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    @Test
    void freshDatabaseMigratesValidatesAndRerunsWithoutChanges() throws SQLException {
        var flyway = flyway(null);
        // Count from resources so later migrations do not break this room-only test.
        int pending = flyway.info().pending().length;
        assertThat(pending).isGreaterThanOrEqualTo(9);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(pending);
        assertThat(flyway.info().pending()).isEmpty();
        flyway.validate();
        assertThat(rows("select conname from pg_constraint where conrelid = 'users'::regclass and contype = 'c'"))
            .containsExactly(List.of("ck_users_nickname"));
        var history = rows("select version, checksum, success from flyway_schema_history order by installed_rank");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(rows("select version, checksum, success from flyway_schema_history order by installed_rank")).isEqualTo(history);
        assertThat(rows("select tablename from pg_tables where schemaname = 'public' order by tablename"))
            .contains(List.of("flyway_schema_history"), List.of("meeting_members"), List.of("meeting_rooms"),
                List.of("recommendation_results"), List.of("recommendation_runs"), List.of("refresh_tokens"),
                List.of("room_messages"), List.of("station_proposal_votes"), List.of("station_proposals"),
                List.of("stations"), List.of("users"));
        assertThat(rows("select column_name from information_schema.columns where table_name = 'meeting_rooms' and column_name = 'status'"))
            .isEmpty();
        assertThat(rows("select column_default from information_schema.columns where table_name in "
            + "('meeting_rooms','meeting_members','station_proposals','station_proposal_votes','recommendation_runs','recommendation_results','room_messages') and column_name = 'id'"))
            .allSatisfy(row -> assertThat(row.get(0)).isNull());
        assertThat(rows("select current_setting('server_version_num')::int / 10000")).containsExactly(List.of(16));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "2", "3", "8"})
    void upgradePreservesAllLegacyRowsAndAppliedChecksums(String version) throws SQLException {
        flyway(version).migrate();
        UUID user = user();
        if (!version.equals("1")) {
            exec("insert into refresh_tokens(id,user_id,family_id,token_hash,expires_at,revoked_at) values (?,?,?,?,now()+interval '1 day',now())",
                id(), user, id(), "b".repeat(64));
        }
        var users = rows("select * from users order by id");
        var tokens = version.equals("1") ? List.of() : rows("select * from refresh_tokens order by id");
        var history = rows("select version, checksum from flyway_schema_history order by installed_rank");
        var latest = flyway(null);
        int pending = latest.info().pending().length;
        assertThat(pending).isGreaterThanOrEqualTo(9 - Integer.parseInt(version));
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(pending);
        assertThat(latest.info().pending()).isEmpty();
        latest.validate();
        assertThat(rows("select * from users order by id")).isEqualTo(users);
        assertThat(rows("select * from refresh_tokens order by id")).isEqualTo(tokens);
        assertThat(rows("select version, checksum from flyway_schema_history where version::int <= " + version + " order by installed_rank"))
            .isEqualTo(history);
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }

    @Test
    void defaultsNullableDepartureAndUnicodeRoomNameBoundaries() throws SQLException {
        fixture();
        assertThat(rows("select max_members, selected_station_id is null, confirmed_at is null, appointment_at is null, created_at is not null, updated_at is not null from meeting_rooms where id = ?", room))
            .containsExactly(List.of(4, true, true, true, true, true));
        assertThat(rows("select role, departure_station_id is null, joined_at is not null from meeting_members where room_id = ? and user_id = ?", room, member))
            .containsExactly(List.of("MEMBER", true, true));
        exec("update meeting_rooms set name = ? where id = ?", "가", room);
        exec("update meeting_rooms set name = ? where id = ?", "😀".repeat(30), room);
        assertThat(rows("select char_length(name) from meeting_rooms where id = ?", room)).containsExactly(List.of(30));
        exec("update meeting_rooms set name = ' 가 ' where id = ?", room);
        exec("update meeting_rooms set selected_station_id = ?, confirmed_at = now() where id = ?", station, room);
        exec("update meeting_rooms set selected_station_id = null, confirmed_at = null where id = ?", room);
        assertThat(count("station_proposals")).isEqualTo(2);
    }

    static Stream<Arguments> invalidUpdates() {
        return Stream.of(
            Arguments.of("update meeting_rooms set room_code='abc123'", "23514", "ck_room_code"),
            Arguments.of("update meeting_rooms set room_code='ABC!23'", "23514", "ck_room_code"),
            Arguments.of("update meeting_rooms set room_code='AB123'", "23514", "ck_room_code"),
            Arguments.of("update meeting_rooms set name=''", "23514", "ck_room_name"),
            Arguments.of("update meeting_rooms set name='   '", "23514", "ck_room_name"),
            Arguments.of("update meeting_rooms set max_members=3", "23514", "ck_room_max"),
            Arguments.of("update meeting_rooms set max_members=5", "23514", "ck_room_max"),
            Arguments.of("update meeting_rooms set selected_station_id=(select min(id) from stations)", "23514", "ck_room_confirmed"),
            Arguments.of("update meeting_rooms set confirmed_at=now()", "23514", "ck_room_confirmed"),
            Arguments.of("update meeting_members set role='ADMIN'", "23514", "ck_member_role"),
            Arguments.of("update stations set latitude=90.1", "23514", "ck_stations_latlng"),
            Arguments.of("update stations set longitude=-180.1", "23514", "ck_stations_latlng"),
            Arguments.of("update recommendation_runs set criteria='[]'::jsonb", "23514", "ck_runs_criteria"),
            Arguments.of("update recommendation_runs set criteria='null'::jsonb", "23514", "ck_runs_criteria"),
            Arguments.of("update recommendation_results set criterion='OTHER'", "23514", "ck_res_criterion"),
            Arguments.of("update recommendation_results set reason_source='OTHER'", "23514", "ck_res_reason_src"),
            Arguments.of("update recommendation_results set metric_value=-0.01", "23514", "ck_res_metric"),
            Arguments.of("update recommendation_results set trips='{}'::jsonb", "23514", "ck_res_trips"),
            Arguments.of("update recommendation_results set trips='null'::jsonb", "23514", "ck_res_trips"),
            Arguments.of("update recommendation_results set nearby='{}'::jsonb", "23514", "ck_res_nearby"),
            Arguments.of("update room_messages set sender_type='OTHER'", "23514", "ck_msg_type"),
            Arguments.of("update room_messages set sender_type='USER'", "23514", "ck_msg_sender"),
            Arguments.of("update room_messages set sender_id=(select id from users limit 1)", "23514", "ck_msg_sender"),
            Arguments.of("update room_messages set content=''", "23514", "ck_msg_content"),
            Arguments.of("update room_messages set content=repeat('a',4001)", "23514", "ck_msg_content")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidUpdates")
    void namedChecksRejectInvalidUpdatesAtomically(String sql, String state, String constraint) throws SQLException {
        fixture();
        String table = sql.split(" ")[1];
        var before = rows("select * from " + table + " order by id");
        failure(sql, state, constraint);
        assertThat(rows("select * from " + table + " order by id")).isEqualTo(before);
    }

    @Test
    void uniqueConstraintsAndOwnerPartialIndexRejectDuplicates() throws SQLException {
        fixture();
        failure("insert into meeting_rooms(id,room_code,name,created_by) values (?,'ROOM01','room',?)", "23505", "uq_room_code", id(), owner);
        failure("insert into meeting_members(id,room_id,user_id) values (?,?,?)", "23505", "uq_member_room_user", id(), room, member);
        failure("insert into meeting_members(id,room_id,user_id,role) values (?,?,?,'OWNER')", "23505", "uq_one_owner_per_room", id(), room, outsider);
        failure("update meeting_members set role='OWNER' where room_id=? and user_id=?", "23505", "uq_one_owner_per_room", room, member);
        // Multiple MEMBERs and the same OWNER in different rooms are legal.
        exec("insert into meeting_members(id,room_id,user_id) values (?,?,?)", id(), room, outsider);
        failure("insert into station_proposals(id,room_id,station_id,proposed_by) values (?,?,?,?)", "23505", "uq_proposal_room_station", id(), room, station, member);
        failure("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,0,'[]','[]','reason','AI')",
            "23505", "uq_result_run_criterion", id(), run, station);
        vote(room, proposal, member);
        failure("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", "23505", "uq_vote_room_user", id(), room, proposal, member);
        UUID anotherProposal = proposal(room, otherStation, owner, null);
        failure("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", "23505", "uq_vote_room_user", id(), room, anotherProposal, member);
        vote(otherRoom, otherProposal, member);
    }

    @Test
    void compositeForeignKeysRejectNonmembersAndCandidatesFromOtherRooms() throws SQLException {
        fixture();
        failure("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", "23503", "fk_vote_member", id(), room, proposal, outsider);
        // member belongs to both rooms, so only the candidate's room is invalid.
        failure("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", "23503", "fk_vote_proposal", id(), room, otherProposal, member);
        vote(room, proposal, member);
        failure("update station_proposal_votes set user_id=?", "23503", "fk_vote_member", outsider);
        failure("update station_proposal_votes set proposal_id=?", "23503", "fk_vote_proposal", otherProposal);
    }

    @Test
    void allOrdinaryForeignKeysRejectMissingTargets() throws SQLException {
        fixture();
        UUID missing = id();
        failure("update meeting_rooms set created_by=?", "23503", "fk_room_creator", missing);
        failure("update meeting_rooms set selected_station_id=-1,confirmed_at=now()", "23503", "fk_room_selected_station");
        failure("update meeting_members set room_id=? where user_id=? and room_id=?", "23503", "fk_member_room", missing, owner, room);
        failure("update meeting_members set user_id=? where user_id=? and room_id=?", "23503", "fk_member_user", missing, owner, room);
        failure("update meeting_members set departure_station_id=-1", "23503", "fk_member_departure_station");
        failure("update station_proposals set room_id=? where id=?", "23503", "fk_proposal_room", missing, proposal);
        failure("update station_proposals set station_id=-1", "23503", "fk_proposal_station");
        failure("update station_proposals set proposed_by=?", "23503", "fk_proposal_author", missing);
        failure("update station_proposals set source_result_id=?", "23503", "fk_proposal_source", missing);
        failure("update recommendation_runs set room_id=?", "23503", "fk_run_room", missing);
        failure("update recommendation_runs set requested_by=?", "23503", "fk_run_requester", missing);
        failure("update recommendation_results set run_id=?", "23503", "fk_result_run", missing);
        failure("update recommendation_results set station_id=-1", "23503", "fk_result_station");
        failure("update room_messages set room_id=?", "23503", "fk_message_room", missing);
        failure("update room_messages set sender_type='USER',sender_id=?", "23503", "fk_message_sender", missing);
    }

    @Test
    void notNullAndLengthsArePreserved() throws SQLException {
        fixture();
        failure("update meeting_rooms set room_code=null", "23502", null);
        failure("update meeting_rooms set name=null", "23502", null);
        failure("update meeting_rooms set max_members=null", "23502", null);
        failure("update meeting_members set role=null", "23502", null);
        failure("update recommendation_results set trips=null", "23502", null);
        failure("update meeting_rooms set name=repeat('a',31)", "22001", null);
        failure("update meeting_rooms set room_code='ABCDEFG'", "22001", null);
    }

    @Test
    void leavingMemberDeletesOnlyTheirVotesAndPreservesProposalsHistoryAndOtherRooms() throws SQLException {
        fixture();
        vote(room, proposal, member);
        vote(room, proposal, owner);
        vote(otherRoom, otherProposal, member);
        exec("insert into room_messages(id,room_id,sender_type,sender_id,content) values (?,?,'USER',?,'history')", id(), room, member);
        var proposals = rows("select * from station_proposals order by id");
        var runs = rows("select * from recommendation_runs order by id");
        var messages = rows("select * from room_messages order by id");
        exec("delete from meeting_members where room_id=? and user_id=?", room, member);
        assertThat(rows("select room_id,user_id from station_proposal_votes order by room_id,user_id"))
            .containsExactlyInAnyOrder(List.of(room, owner), List.of(otherRoom, member));
        assertThat(rows("select * from station_proposals order by id")).isEqualTo(proposals);
        assertThat(rows("select * from recommendation_runs order by id")).isEqualTo(runs);
        assertThat(rows("select * from room_messages order by id")).isEqualTo(messages);
    }

    @Test
    void deletingCandidateDeletesItsVotesButKeepsConfirmedStationAndAllowsNewVote() throws SQLException {
        fixture();
        vote(room, proposal, member);
        exec("update meeting_rooms set selected_station_id=?,confirmed_at=now() where id=?", station, room);
        var confirmed = rows("select selected_station_id,confirmed_at from meeting_rooms where id=?", room);
        exec("delete from station_proposals where id=?", proposal);
        assertThat(count("station_proposal_votes")).isZero();
        assertThat(rows("select selected_station_id,confirmed_at from meeting_rooms where id=?", room)).isEqualTo(confirmed);
        UUID next = proposal(room, otherStation, owner, null);
        vote(room, next, member);
        assertThat(count("station_proposal_votes")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recommendation_results", "recommendation_runs"})
    void deletingRecommendationOnlyNullsCandidateSource(String table) throws SQLException {
        fixture();
        vote(room, proposal, member);
        exec("delete from " + table);
        assertThat(count("recommendation_results")).isZero();
        assertThat(rows("select source_result_id is null from station_proposals where id=?", proposal)).containsExactly(List.of(true));
        assertThat(count("station_proposals")).isEqualTo(2);
        assertThat(count("station_proposal_votes")).isEqualTo(1);
    }

    @Test
    void deletingRoomCascadesAllOwnedDataAndPreservesOtherRoomAndSharedData() throws SQLException {
        fixture();
        vote(room, proposal, member);
        vote(otherRoom, otherProposal, member);
        UUID otherRun = id();
        exec("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')", otherRun, otherRoom, owner);
        exec("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'FARE',?,0,'[]','[]','reason','TEMPLATE')", id(), otherRun, station);
        exec("insert into room_messages(id,room_id,sender_type,content) values (?,?,'AI','reply')", id(), otherRoom);
        var otherMembers = rows("select * from meeting_members where room_id=? order by id", otherRoom);
        var otherVotes = rows("select * from station_proposal_votes where room_id=? order by id", otherRoom);
        exec("delete from meeting_rooms where id=?", room);
        for (String table : List.of("meeting_members", "station_proposals", "station_proposal_votes", "recommendation_runs", "room_messages")) {
            assertThat(rows("select count(*) from " + table + " where room_id=?", room)).containsExactly(List.of(0L));
        }
        assertThat(count("recommendation_results")).isEqualTo(1);
        assertThat(rows("select * from meeting_members where room_id=? order by id", otherRoom)).isEqualTo(otherMembers);
        assertThat(rows("select * from station_proposal_votes where room_id=? order by id", otherRoom)).isEqualTo(otherVotes);
        assertThat(count("users")).isEqualTo(3);
        assertThat(count("stations")).isEqualTo(2);
    }

    @Test
    void sharedUserAndStationReferencesUseNoActionRatherThanCascadeOrSetNull() throws SQLException {
        fixture();
        failure("delete from users where id=?", "23503", null, owner);
        failure("delete from users where id=?", "23503", null, member);
        failure("delete from stations where id=?", "23503", null, station);
        // Soft deletion does not trigger FKs: membership cleanup belongs to the withdrawal service.
        exec("update users set deleted_at=now() where id=?", member);
        assertThat(count("meeting_members")).isEqualTo(4);
    }

    @Test
    void allDocumentedIndexesAndForeignKeyDeletionActionsExist() throws SQLException {
        fixture();
        assertThat(rows("select indexname from pg_indexes where schemaname='public'"))
            .contains(List.of("idx_stations_name"), List.of("idx_stations_lat_lng"), List.of("uq_one_owner_per_room"),
                List.of("idx_members_user"), List.of("idx_members_room_joined_id"), List.of("idx_runs_room_created"),
                List.of("idx_proposals_room_created"), List.of("idx_proposals_author"), List.of("idx_proposals_source"),
                List.of("idx_votes_proposal"), List.of("idx_messages_room_id"));
        assertThat(rows("select conname,confdeltype::text from pg_constraint where contype='f' and connamespace='public'::regnamespace"))
            .contains(List.of("fk_vote_member", "c"), List.of("fk_vote_proposal", "c"), List.of("fk_vote_room", "c"),
                List.of("fk_member_room", "c"), List.of("fk_proposal_room", "c"), List.of("fk_run_room", "c"),
                List.of("fk_result_run", "c"), List.of("fk_message_room", "c"), List.of("fk_proposal_source", "n"),
                List.of("fk_room_creator", "a"), List.of("fk_room_selected_station", "a"), List.of("fk_member_user", "a"),
                List.of("fk_member_departure_station", "a"), List.of("fk_proposal_station", "a"), List.of("fk_proposal_author", "a"),
                List.of("fk_run_requester", "a"), List.of("fk_result_station", "a"), List.of("fk_message_sender", "a"));
    }

    private void fixture() throws SQLException {
        flyway(null).migrate();
        owner = user(); member = user(); outsider = user();
        station = ((Number) rows("insert into stations(name,latitude,longitude) values ('station',37,127) returning id").get(0).get(0)).longValue();
        otherStation = ((Number) rows("insert into stations(name,latitude,longitude) values ('other',-90,180) returning id").get(0).get(0)).longValue();
        room = room("ROOM01"); otherRoom = room("ROOM02");
        for (UUID roomId : List.of(room, otherRoom)) {
            exec("insert into meeting_members(id,room_id,user_id,role) values (?,?,?,'OWNER')", id(), roomId, owner);
            exec("insert into meeting_members(id,room_id,user_id) values (?,?,?)", id(), roomId, member);
        }
        run = id(); result = id();
        exec("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')", run, room, member);
        exec("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,0,'[]','[]','reason','AI')", result, run, station);
        proposal = proposal(room, station, member, result);
        otherProposal = proposal(otherRoom, station, owner, null);
        exec("insert into room_messages(id,room_id,sender_type,content) values (?,?,'SYSTEM','created')", id(), room);
    }

    private UUID user() throws SQLException {
        UUID user = id();
        exec("insert into users(id,kakao_id,nickname) values (?,?,'user')", user, user.toString());
        return user;
    }

    private UUID room(String code) throws SQLException {
        UUID value = id();
        exec("insert into meeting_rooms(id,room_code,name,created_by) values (?,?,'room',?)", value, code, owner);
        return value;
    }

    private UUID proposal(UUID roomId, long stationId, UUID author, UUID source) throws SQLException {
        UUID value = id();
        exec("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (?,?,?,?,?)", value, roomId, stationId, author, source);
        return value;
    }

    private void vote(UUID roomId, UUID candidate, UUID voter) throws SQLException {
        exec("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", id(), roomId, candidate, voter);
    }

    private static UUID id() { return UuidCreator.getTimeOrderedEpoch(); }

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

    private long count(String table) throws SQLException {
        return ((Number) rows("select count(*) from " + table).get(0).get(0)).longValue();
    }

    private void failure(String sql, String state, String constraint, Object... params) {
        assertThatThrownBy(() -> exec(sql, params)).isInstanceOfSatisfying(SQLException.class, exception -> {
            assertThat(exception.getSQLState()).isEqualTo(state);
            if (constraint != null) { assertThat(exception.getMessage()).contains("\"" + constraint + "\""); }
        });
    }
}
