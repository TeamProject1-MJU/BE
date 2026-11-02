package com.mju.linkro.room;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(45)
class RoomApiPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @Autowired MeetingRoomRepository rooms;
    @Autowired MeetingMemberRepository members;
    @MockitoBean RoomCodeGenerator codes;
    @Autowired RoomCreationService creation;
    @Autowired RoomService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    private final JsonMapper mapper = JsonMapper.builder().build();
    MockMvc mvc;
    UUID owner, member, outsider;

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from users");
        owner = users.saveAndFlush(new User("owner", "방장")).getId();
        member = users.saveAndFlush(new User("member", "참여자")).getId();
        outsider = users.saveAndFlush(new User("outsider", "외부인")).getId();
        AtomicInteger count = new AtomicInteger();
        when(codes.generate()).thenAnswer(invocation -> String.format("A%05d", count.incrementAndGet()));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    private RoomWriteRequest request(String json) { return mapper.readValue(json, RoomWriteRequest.class); }
    private UUID room() { return creation.create(owner, request("{\"name\":\"original\",\"appointmentAt\":\"2026-11-02T12:00:00Z\"}")).id(); }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, UUID user) {
        return request.header("Authorization", "Bearer " + jwt.createAccessToken(user));
    }
    private ResultActions json(MockHttpServletRequestBuilder req, UUID user, String body) throws Exception {
        return mvc.perform(auth(req, user).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private void addMember(UUID room) { members.saveAndFlush(new MeetingMember(room, member, RoomRole.MEMBER)); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }

    @Test void createdContractAndAtomicOwner() throws Exception {
        var response = json(post("/api/v1/rooms").param("userId", outsider.toString()), owner, "{\"name\":\"  약속  \",\"ownerId\":\"" + outsider + "\",\"userId\":\"" + outsider + "\",\"maxMembers\":99}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.name").value("약속"))
                .andExpect(jsonPath("$.data.ownerId").value(owner.toString())).andExpect(jsonPath("$.data.maxMembers").value(4))
                .andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.appointmentAt").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        var data = mapper.readTree(response).get("data");
        assertThat(data.size()).isEqualTo(7);
        assertThat(data.get("roomCode").asString()).matches("[A-Z0-9]{6}");
        assertThat(count("meeting_rooms")).isEqualTo(1);
        assertThat(count("meeting_members")).isEqualTo(1);
        assertThat(jdbc.queryForMap("select created_by from meeting_rooms").get("created_by")).isEqualTo(owner);
        assertThat(jdbc.queryForMap("select user_id,role,departure_station_id from meeting_members"))
                .containsEntry("user_id", owner).containsEntry("role", "OWNER").containsEntry("departure_station_id", null);
    }

    @Test void collisionRetriesInFreshTransactionAndKeepsExactlyOneOwner() {
        UUID existing = room();
        when(codes.generate()).thenReturn("A00001", "B00002");
        UUID created = room();
        assertThat(created).isNotEqualTo(existing);
        assertThat(rooms.findById(created).orElseThrow().getRoomCode()).isEqualTo("B00002");
        assertThat(count("meeting_rooms")).isEqualTo(2);
        assertThat(count("meeting_members")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from meeting_members where room_id=? and role='OWNER'", Integer.class, created)).isEqualTo(1);
    }

    @Test void exhaustedCollisionsUseInternalErrorAndLeaveNoPartialRoom() throws Exception {
        room();
        clearInvocations(codes);
        when(codes.generate()).thenReturn("A00001");
        json(post("/api/v1/rooms"), owner, "{\"name\":\"new\"}")
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
        verify(codes, times(5)).generate();
        assertThat(count("meeting_rooms")).isEqualTo(1);
        assertThat(count("meeting_members")).isEqualTo(1);
    }

    @Test void failureAfterMemberInsertRollsBackBothAndDoesNotRetry() {
        // Test-only AFTER INSERT trigger fails after both INSERTs reached the database.
        jdbc.execute("create function fail_test_owner() returns trigger language plpgsql as $$ begin raise exception 'injected owner failure' using errcode='23514', constraint='test_owner_failure'; end $$");
        jdbc.execute("create trigger fail_test_owner after insert on meeting_members for each row execute function fail_test_owner()");
        try {
            assertThatThrownBy(this::room).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("meeting_rooms")).isZero();
            assertThat(count("meeting_members")).isZero();
            verify(codes).generate();
        } finally {
            jdbc.execute("drop trigger fail_test_owner on meeting_members");
            jdbc.execute("drop function fail_test_owner()");
        }
    }

    @Test void unrelatedIntegrityViolationIsNotRetried() {
        when(codes.generate()).thenReturn("bad");
        assertThatThrownBy(this::room).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(codes).generate();
        assertThat(count("meeting_rooms")).isZero();
        assertThat(count("meeting_members")).isZero();
    }

    @Test void listsOnlyParticipatingAndEmptyArrayAndNeverExposesCode() throws Exception {
        UUID room = room(); addMember(room);
        creation.create(outsider, request("{\"name\":\"other\"}"));
        for (UUID user : List.of(owner, member)) {
            var result = mvc.perform(auth(get("/api/v1/rooms"), user)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].id").value(room.toString()))
                    .andExpect(jsonPath("$.data[0].memberCount").value(2)).andExpect(jsonPath("$.data[0].maxMembers").value(4))
                    .andExpect(jsonPath("$.data[0].myRole").value(user.equals(owner) ? "OWNER" : "MEMBER"))
                    .andExpect(jsonPath("$.data[0].selectedStation").value(org.hamcrest.Matchers.nullValue())).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("roomCode", "A00001");
        }
        UUID empty = users.saveAndFlush(new User("empty", "새사용자")).getId();
        mvc.perform(auth(get("/api/v1/rooms"), empty)).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test void detailedMembersUseFormalStationLinesAndCurrentOwner() throws Exception {
        UUID room = room(); addMember(room);
        long station = jdbc.queryForObject("select min(id) from stations where exists (select 1 from station_lines sl where sl.station_id=stations.id)", Long.class);
        jdbc.update("update meeting_members set role='MEMBER' where room_id=? and user_id=?", room, owner);
        jdbc.update("update meeting_members set role='OWNER',departure_station_id=? where room_id=? and user_id=?", station, room, member);
        jdbc.update("update meeting_rooms set selected_station_id=?,confirmed_at='2026-11-01T12:00:00Z' where id=?", station, room);
        for (UUID user : List.of(owner, member)) {
            var detail = mvc.perform(auth(get("/api/v1/rooms/" + room), user)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ownerId").value(member.toString()))
                    .andExpect(jsonPath("$.data.myRole").value(user.equals(member) ? "OWNER" : "MEMBER"))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.selectedStation.id").value(station))
                    .andExpect(jsonPath("$.data.confirmedAt").value("2026-11-01T12:00:00Z"))
                    .andExpect(jsonPath("$.data.members[0].departureStation").value(org.hamcrest.Matchers.nullValue()))
                    .andExpect(jsonPath("$.data.members[1].departureStation.isSupported").isBoolean())
                    .andExpect(jsonPath("$.data.members[1].departureStation.lines[0].lineId").isNumber())
                    .andExpect(jsonPath("$.data.members[1].departureStation.lines[0].lineName").isString())
                    .andExpect(jsonPath("$.data.members[1].departureStation.lines[0].color").isString()).andReturn();
            assertThat(detail.getResponse().getContentAsString()).doesNotContain("roomCode", "A00001", "proposals");
            var list = mvc.perform(auth(get("/api/v1/rooms/" + room + "/members"), user)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].joinedAt").isString()).andExpect(jsonPath("$.data[1].departureStation.id").value(station)).andReturn();
            assertThat(list.getResponse().getContentAsString()).doesNotContain("roomCode", "A00001");
        }
        mvc.perform(auth(get("/api/v1/rooms/" + room + "/invite-code"), owner)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ROOM_OWNER"));
        mvc.perform(auth(get("/api/v1/rooms/" + room + "/invite-code"), member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomCode").value("A00001"));
        json(patch("/api/v1/rooms/" + room), owner, "{\"name\":\"no\"}").andExpect(status().isForbidden());
        json(patch("/api/v1/rooms/" + room), member, "{\"name\":\"yes\"}").andExpect(status().isOk());
        assertThat(rooms.findById(room).orElseThrow().getName()).isEqualTo("yes");
    }

    @ParameterizedTest @ValueSource(strings = {"", "/members", "/invite-code"})
    void readAccessChecks(String suffix) throws Exception {
        UUID room = room(); addMember(room);
        mvc.perform(auth(get("/api/v1/rooms/" + room + suffix), outsider)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
        mvc.perform(auth(get("/api/v1/rooms/" + UUID.randomUUID() + suffix), owner)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        mvc.perform(get("/api/v1/rooms/" + room + suffix)).andExpect(status().isUnauthorized());
    }

    @Test void patchAuthorizationAndMissingRoom() throws Exception {
        UUID room = room(); addMember(room);
        json(patch("/api/v1/rooms/" + room), member, "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_ROOM_OWNER"));
        json(patch("/api/v1/rooms/" + room), outsider, "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
        json(patch("/api/v1/rooms/" + UUID.randomUUID()), owner, "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
    }

    @Test void patchOmissionNullAndIgnoredFields() throws Exception {
        UUID room = room();
        json(patch("/api/v1/rooms/" + room), owner, "{}").andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":null}"));
        assertThat(rooms.findById(room).orElseThrow().getAppointmentAt()).isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
        json(patch("/api/v1/rooms/" + room), owner, "{\"name\":\" next \",\"maxMembers\":99,\"createdBy\":\"" + outsider + "\"}").andExpect(status().isOk());
        var changed = rooms.findById(room).orElseThrow();
        assertThat(changed.getName()).isEqualTo("next");
        assertThat(changed.getMaxMembers()).isEqualTo(4);
        assertThat(changed.getCreatedBy()).isEqualTo(owner);
        assertThat(changed.getAppointmentAt()).isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
        json(patch("/api/v1/rooms/" + room), owner, "{\"appointmentAt\":null}").andExpect(status().isOk());
        changed = rooms.findById(room).orElseThrow();
        assertThat(changed.getName()).isEqualTo("next");
        assertThat(changed.getAppointmentAt()).isNull();
        json(patch("/api/v1/rooms/" + room), owner, "{\"appointmentAt\":\"2026-11-03T21:00:00+09:00\"}").andExpect(status().isOk());
        assertThat(rooms.findById(room).orElseThrow().getAppointmentAt()).isEqualTo(Instant.parse("2026-11-03T12:00:00Z"));
    }

    @ParameterizedTest @ValueSource(strings = {"null", "\"\"", "\" \"", "1", "true", "[]", "{}"})
    void invalidNameApi(String name) throws Exception {
        json(post("/api/v1/rooms"), owner, "{\"name\":" + name + "}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        UUID room = room();
        json(patch("/api/v1/rooms/" + room), owner, "{\"name\":" + name + "}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(rooms.findById(room).orElseThrow().getName()).isEqualTo("original");
    }

    @Test void unicodeBoundariesAndMalformedRequests() throws Exception {
        for (String name : List.of("a", "😀".repeat(30), "\t", "\u2003")) {
            json(post("/api/v1/rooms"), owner, mapper.writeValueAsString(Map.of("name", name))).andExpect(status().isCreated());
        }
        for (String body : List.of("{}", "null", "[]", "{", "{\"name\":\"a\",\"appointmentAt\":\"bad\"}",
                "{\"name\":\"a\",\"appointmentAt\":42}", mapper.writeValueAsString(Map.of("name", "😀".repeat(31))))) {
            json(post("/api/v1/rooms"), owner, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Test void authenticationAndDenyUnimplementedOperations() throws Exception {
        UUID room = room();
        mvc.perform(post("/api/v1/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"a\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/rooms")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/rooms/" + room).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        for (MockHttpServletRequestBuilder req : List.of(post("/api/v1/rooms/join"), delete("/api/v1/rooms/" + room),
                patch("/api/v1/rooms/" + room + "/members/me/departure"), put("/api/v1/rooms/" + room + "/selected-station"))) {
            mvc.perform(auth(req, owner)).andExpect(status().isForbidden());
        }
        User deleted = users.findById(member).orElseThrow(); deleted.softDelete(); users.saveAndFlush(deleted);
        for (UUID user : List.of(member, UUID.randomUUID())) {
            mvc.perform(auth(get("/api/v1/rooms"), user)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
            json(post("/api/v1/rooms"), user, "{\"name\":\"no\"}").andExpect(status().isUnauthorized());
        }
    }

    @Test void patchPreservesRecommendationsProposalsVotesAndConfirmation() throws Exception {
        UUID room = room(), run = UUID.randomUUID(), result = UUID.randomUUID(), proposal = UUID.randomUUID();
        long station = jdbc.queryForObject("select min(id) from stations", Long.class);
        jdbc.update("update meeting_rooms set selected_station_id=?,confirmed_at='2026-11-01T12:00:00Z' where id=?", station, room);
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')", run, room, owner);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')", result, run, station);
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (?,?,?,?,?)", proposal, room, station, owner, result);
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", UUID.randomUUID(), room, proposal, owner);
        Map<String, List<Map<String, Object>>> before = new HashMap<>();
        for (String table : List.of("recommendation_runs", "recommendation_results", "station_proposals", "station_proposal_votes")) before.put(table, jdbc.queryForList("select * from " + table));
        json(patch("/api/v1/rooms/" + room), owner, "{\"name\":\"updated\",\"appointmentAt\":null}").andExpect(status().isOk());
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("select * from " + table)).isEqualTo(rows));
        var updated = rooms.findById(room).orElseThrow();
        assertThat(updated.getSelectedStationId()).isEqualTo(station);
        assertThat(updated.getConfirmedAt()).isEqualTo(Instant.parse("2026-11-01T12:00:00Z"));
        assertThat(updated.getStatus()).isEqualTo(RoomStatus.CONFIRMED);
    }

    @Test void ownerLockTimeoutReturnsBusyWithoutMutation() throws Exception {
        UUID room = room();
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> holder = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
            jdbc.queryForList("select id from meeting_rooms where id=? for update", room);
            locked.countDown();
            try { if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
            catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            return null;
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            json(patch("/api/v1/rooms/" + room), owner, "{\"name\":\"blocked\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_BUSY"));
        } finally { release.countDown(); holder.get(10, TimeUnit.SECONDS); pool.shutdownNow(); }
        assertThat(rooms.findById(room).orElseThrow().getName()).isEqualTo("original");
    }
}
