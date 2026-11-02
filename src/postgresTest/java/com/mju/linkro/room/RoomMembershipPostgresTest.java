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
class RoomMembershipPostgresTest {
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
    @Autowired RoomJoinService joining;
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


    private com.mju.linkro.room.dto.RoomMembershipRequests.Join joinRequest() {
        return mapper.readValue("{\"roomCode\":\"A00001\"}", com.mju.linkro.room.dto.RoomMembershipRequests.Join.class);
    }
    private String departurePath(UUID room) { return "/api/v1/rooms/" + room + "/members/me"; }
    private long station() { return jdbc.queryForObject("select min(id) from stations where is_supported", Long.class); }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void joinsOpenAndConfirmedWithoutChangingRoom(boolean confirmed) throws Exception {
        UUID room = room();
        if (confirmed) jdbc.update("update meeting_rooms set selected_station_id=?,confirmed_at=now() where id=?", station(), room);
        var before = jdbc.queryForMap("select * from meeting_rooms where id=?", room);
        json(post("/api/v1/rooms/join").param("userId", outsider.toString()), member,
                "{\"roomCode\":\"A00001\",\"userId\":\""+outsider+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roomId").value(room.toString()));
        var joined = members.findByRoomIdAndUserId(room,member).orElseThrow();
        assertThat(joined.getRole()).isEqualTo(RoomRole.MEMBER);
        assertThat(joined.getDepartureStationId()).isNull();
        assertThat(members.findByRoomIdAndUserId(room,outsider)).isEmpty();
        assertThat(jdbc.queryForMap("select * from meeting_rooms where id=?",room)).isEqualTo(before);
    }

    @Test void existingMemberOfFullRoomGetsAlreadyJoined() throws Exception {
        UUID room=room(); addMember(room);
        members.saveAndFlush(new MeetingMember(room,outsider,RoomRole.MEMBER));
        UUID fourth=users.saveAndFlush(new User("fourth","fourth")).getId();
        members.saveAndFlush(new MeetingMember(room,fourth,RoomRole.MEMBER));
        json(post("/api/v1/rooms/join"),member,"{\"roomCode\":\"A00001\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ALREADY_JOINED"));
        UUID fifth=users.saveAndFlush(new User("fifth","fifth")).getId();
        json(post("/api/v1/rooms/join"),fifth,"{\"roomCode\":\"A00001\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_FULL"));
        assertThat(members.countByRoomId(room)).isEqualTo(4);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void simultaneousJoinsHaveOneWinner(boolean sameUser) throws Exception {
        UUID room=room();
        if (!sameUser) { addMember(room); members.saveAndFlush(new MeetingMember(room,outsider,RoomRole.MEMBER)); }
        UUID a=users.saveAndFlush(new User("a","userA")).getId();
        UUID b=sameUser?a:users.saveAndFlush(new User("b","userB")).getId();
        UUID c=sameUser?a:users.saveAndFlush(new User("c","userC")).getId();
        CountDownLatch ready=new CountDownLatch(3), start=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(3);
        try (var holder = POSTGRES.createConnection("")) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("select id from meeting_rooms where id=? for no key update")) {
                statement.setObject(1, room);
                statement.executeQuery().close();
            }
            List<Future<String>> results=new ArrayList<>();
            for (UUID user:List.of(a,b,c)) results.add(pool.submit(() -> {
                ready.countDown(); assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                try { joining.join(user,joinRequest()); return "OK"; }
                catch (BusinessException e) { return e.getErrorCode().name(); }
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            // The three service transactions must overlap on separate backend connections.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            List<Integer> waiting = List.of();
            while (System.nanoTime() < deadline) {
                waiting = jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and query like '%meeting_rooms%' and pid<>pg_backend_pid()", Integer.class);
                if (waiting.size() == 3) break;
                Thread.sleep(10);
            }
            assertThat(waiting).hasSize(3).doesNotHaveDuplicates();
            holder.commit();
            List<String> outcomes=new ArrayList<>();
            for (var result:results) outcomes.add(result.get(15,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("OK",sameUser?"ALREADY_JOINED":"ROOM_FULL",sameUser?"ALREADY_JOINED":"ROOM_FULL");
            assertThat(members.countByRoomId(room)).isEqualTo(sameUser?2:4);
            assertThat(jdbc.queryForObject("select count(*) from (select user_id from meeting_members where room_id=? group by user_id having count(*)>1) d",Integer.class,room)).isZero();
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test void departureValidatesAndOnlyChangesCaller() throws Exception {
        UUID room=room(); addMember(room);
        List<Long> ids=jdbc.queryForList("select id from stations where is_supported order by id limit 2",Long.class);
        for (UUID user:List.of(owner,member)) for (long id:ids) {
            UUID other = user.equals(owner) ? member : owner;
            var othersBefore = jdbc.queryForList("select * from meeting_members where room_id=? and user_id<>?", room, user);
            json(patch(departurePath(room)).param("userId",other.toString()),user,
                "{\"departureStationId\":"+id+",\"userId\":\""+other+"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
            assertThat(members.findByRoomIdAndUserId(room,user).orElseThrow().getDepartureStationId()).isEqualTo(id);
            assertThat(jdbc.queryForList("select * from meeting_members where room_id=? and user_id<>?", room, user))
                    .isEqualTo(othersBefore);
        }
        assertThat(members.findByRoomIdAndUserId(room,outsider)).isEmpty();
        jdbc.update("update stations set is_supported=false where id=?",ids.get(0));
        try {
            for (String body:List.of("{}","{\"departureStationId\":null}","{\"departureStationId\":\"105\"}","{\"departureStationId\":1.5}","{\"departureStationId\":true}","{\"departureStationId\":[]}","{\"departureStationId\":{}}","{\"departureStationId\":9223372036854775808}"))
                json(patch(departurePath(room)),member,body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
            json(patch(departurePath(room)),member,"{\"departureStationId\":-1}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("STATION_NOT_FOUND"));
            json(patch(departurePath(room)),member,"{\"departureStationId\":"+ids.get(0)+"}").andExpect(status().is(422)).andExpect(jsonPath("$.error.code").value("STATION_UNSUPPORTED"));
            json(patch(departurePath(room)),outsider,"{\"departureStationId\":"+ids.get(1)+"}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
            json(patch(departurePath(UUID.randomUUID())),member,"{\"departureStationId\":"+ids.get(1)+"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
            assertThat(members.findByRoomIdAndUserId(room,member).orElseThrow().getDepartureStationId()).isEqualTo(ids.get(1));
            assertThat(members.findByRoomIdAndUserId(room,owner).orElseThrow().getDepartureStationId()).isEqualTo(ids.get(1));
        } finally { jdbc.update("update stations set is_supported=true where id=?",ids.get(0)); }
    }

    @Test void malformedCodesAndUnauthenticatedRequests() throws Exception {
        UUID room=room();
        for (String body:List.of("{}","{\"roomCode\":null}","{\"roomCode\":123456}","{\"roomCode\":true}","{\"roomCode\":[]}","{\"roomCode\":{}}","{\"roomCode\":\"a00001\"}","{\"roomCode\":\" A00001\"}","{\"roomCode\":\"A0000\"}","{\"roomCode\":\"A000001\"}","{\"roomCode\":\"A0000!\"}"))
            json(post("/api/v1/rooms/join"),member,body).andExpect(status().isBadRequest());
        json(post("/api/v1/rooms/join"),member,"{\"roomCode\":\"ZZZZZZ\"}").andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROOM_CODE_INVALID"));
        for (var req:List.of(post("/api/v1/rooms/join"),patch(departurePath(room))))
            mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
        assertThat(members.countByRoomId(room)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "true", "1", "\"text\"", "{", ""})
    void malformedBodiesAndPathDoNotChangeMembership(String body) throws Exception {
        UUID room = room();
        var before = jdbc.queryForList("select * from meeting_members where room_id=?", room);
        for (var request : List.of(post("/api/v1/rooms/join"), patch(departurePath(room)))) {
            json(request, owner, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        json(patch(departurePath(room).replace(room.toString(), "invalid-uuid")), owner, "{\"departureStationId\":105}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForList("select * from meeting_members where room_id=?", room)).isEqualTo(before);
    }
    @Test void membershipPreservesRecommendationsProposalsVotesAndConfirmation() throws Exception {
        UUID room = room(), run = UUID.randomUUID(), result = UUID.randomUUID(), proposal = UUID.randomUUID();
        long station = jdbc.queryForObject("select min(id) from stations", Long.class);
        jdbc.update("update meeting_rooms set selected_station_id=?,confirmed_at='2026-11-01T12:00:00Z' where id=?", station, room);
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')", run, room, owner);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')", result, run, station);
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (?,?,?,?,?)", proposal, room, station, owner, result);
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)", UUID.randomUUID(), room, proposal, owner);
        Map<String, List<Map<String, Object>>> before = new HashMap<>();
        for (String table : List.of("recommendation_runs", "recommendation_results", "station_proposals", "station_proposal_votes")) before.put(table, jdbc.queryForList("select * from " + table));
        json(post("/api/v1/rooms/join"), member, "{\"roomCode\":\"A00001\"}").andExpect(status().isOk());
        json(patch(departurePath(room)), member, "{\"departureStationId\":"+station+"}").andExpect(status().isOk());
        before.forEach((table, rows) -> assertThat(jdbc.queryForList("select * from " + table)).isEqualTo(rows));
        var updated = rooms.findById(room).orElseThrow();
        assertThat(updated.getSelectedStationId()).isEqualTo(station);
        assertThat(updated.getConfirmedAt()).isEqualTo(Instant.parse("2026-11-01T12:00:00Z"));
        assertThat(updated.getStatus()).isEqualTo(RoomStatus.CONFIRMED);
    }


    @ParameterizedTest @ValueSource(booleans={false,true})
    void lockTimeoutRollsBackJoinAndDeparture(boolean departure) throws Exception {
        UUID room=room();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        Future<?> holder=pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
            jdbc.queryForList("select id from meeting_rooms where id=? for no key update",room);
            locked.countDown();
            try { assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            return null;
        }));
        try {
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            json(departure?patch(departurePath(room)):post("/api/v1/rooms/join"),departure?owner:member,
                departure?"{\"departureStationId\":"+station()+"}":"{\"roomCode\":\"A00001\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_BUSY"));
        } finally { release.countDown(); holder.get(10,TimeUnit.SECONDS); pool.shutdownNow(); }
        assertThat(members.countByRoomId(room)).isEqualTo(1);
        assertThat(members.findByRoomIdAndUserId(room,owner).orElseThrow().getDepartureStationId()).isNull();
    }

    @Test void deletedWhileWaitingForLockIsInvalidCode() throws Exception {
        UUID room=room();
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try (var connection=POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try (var stmt=connection.prepareStatement("delete from meeting_rooms where id=?")) {
                stmt.setObject(1,room); stmt.executeUpdate();
            }
            Future<String> waiter=pool.submit(() -> {
                try { joining.join(member,joinRequest()); return "OK"; }
                catch (BusinessException e) { return e.getErrorCode().name(); }
            });
            // Observe an actual PostgreSQL lock wait on a separate connection before committing deletion.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            boolean waiting=false;
            while (System.nanoTime()<deadline) {
                waiting=jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%meeting_rooms%' and pid<>pg_backend_pid())",Boolean.class);
                if (waiting) break;
                Thread.sleep(10);
            }
            assertThat(waiting).isTrue();
            connection.commit();
            assertThat(waiter.get(10,TimeUnit.SECONDS)).isEqualTo("ROOM_CODE_INVALID");
            assertThat(members.countByRoomId(room)).isZero();
        } finally { pool.shutdownNow(); }
    }

    @Test void generatedOpenApiKeepsBearerHiddenUserAndUniqueSchemas() throws Exception {
        var spec=mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(spec.at("/components/securitySchemes/bearerAuth/type").asString()).isEqualTo("http");
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        for (String pointer:List.of("/paths/~1api~1v1~1rooms~1join/post","/paths/~1api~1v1~1rooms~1{roomId}~1members~1me/patch")) {
            var operation=spec.at(pointer);
            assertThat(operation.get("security").toString()).contains("bearerAuth");
            assertThat(operation.path("parameters").toString()).doesNotContain("userId");
            String ref=operation.at("/requestBody/content/application~1json/schema/$ref").asString();
            assertThat(spec.at(ref.substring(1)).isMissingNode()).isFalse();
            String responseRef=operation.at("/responses/200/content/application~1json/schema/$ref").asString();
            assertThat(spec.at(responseRef.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(spec.at("/components/securitySchemes/bearerAuth/bearerFormat").asString()).isEqualTo("JWT");
        assertThat(spec.at("/components/schemas/RoomJoinRequest/required").toString()).contains("roomCode");
        assertThat(spec.at("/components/schemas/RoomJoinRequest/properties/roomCode/type").asString()).isEqualTo("string");
        assertThat(spec.at("/components/schemas/RoomDepartureUpdateRequest/required").toString()).contains("departureStationId");
        assertThat(spec.at("/components/schemas/RoomDepartureUpdateRequest/properties/departureStationId/type").asString()).isEqualTo("integer");
        assertThat(spec.at("/components/schemas/RoomDepartureUpdateRequest/properties/departureStationId/format").asString()).isEqualTo("int64");
        var parameter=spec.at("/paths/~1api~1v1~1rooms~1{roomId}~1members~1me/patch/parameters/0");
        assertThat(parameter.path("name").asString()).isEqualTo("roomId");
        assertThat(parameter.path("required").asBoolean()).isTrue();
        assertThat(parameter.at("/schema/format").asString()).isEqualTo("uuid");
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void translatesOnlyActualMemberUniqueViolationAfterRollback(boolean unique) {
        UUID room=room();
        String sql=unique?
            "if pg_trigger_depth()=1 then insert into meeting_members(id,room_id,user_id,role) values (gen_random_uuid(),new.room_id,new.user_id,'MEMBER'); end if; return new;":
            "raise exception 'injected integrity failure' using errcode='23514', constraint='other_check';";
        jdbc.execute("create function membership_failure() returns trigger language plpgsql as $$ begin "+sql+" end $$");
        jdbc.execute("create trigger membership_failure before insert on meeting_members for each row execute function membership_failure()");
        try {
            if (unique) assertThatThrownBy(() -> joining.join(member,joinRequest())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_JOINED));
            else assertThatThrownBy(() -> joining.join(member,joinRequest())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(members.countByRoomId(room)).isEqualTo(1);
        } finally {
            jdbc.execute("drop trigger membership_failure on meeting_members");
            jdbc.execute("drop function membership_failure()");
        }
        // A fresh request must work after rollback; no poisoned transaction or partial member survives.
        joining.join(member,joinRequest());
        assertThat(members.countByRoomId(room)).isEqualTo(2);
    }
}
