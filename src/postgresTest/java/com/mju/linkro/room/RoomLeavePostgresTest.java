package com.mju.linkro.room;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class RoomLeavePostgresTest {
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
    @Autowired RoomMembershipService membership;
    @Autowired RoomLeaveService leaving;
    @Autowired RoomLeaveProcessor processor;
    @Autowired RoomAccess access;
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
    private void addMember(UUID room) { members.saveAndFlush(new MeetingMember(room, member, RoomRole.MEMBER)); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }


    private com.mju.linkro.room.dto.RoomMembershipRequests.Join joinRequest() {
        return mapper.readValue("{\"roomCode\":\"A00001\"}", com.mju.linkro.room.dto.RoomMembershipRequests.Join.class);
    }
    private String departurePath(UUID room) { return "/api/v1/rooms/" + room + "/members/me"; }
    private long station() { return jdbc.queryForObject("select min(id) from stations where is_supported", Long.class); }

    @Test void memberLeavesOwnerAndUserAccountRemainAndRetryForbidden() throws Exception {
        UUID room=room(); addMember(room);
        mvc.perform(auth(delete(departurePath(room)).param("userId", owner.toString()),member))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roomDeleted").value(false))
                .andExpect(content().json("{\"success\":true,\"data\":{\"roomDeleted\":false,\"newOwnerId\":null}}"));
        assertThat(members.findCurrentRole(room,owner)).contains(RoomRole.OWNER);
        assertThat(users.findById(member)).isPresent();
        mvc.perform(auth(delete(departurePath(room)),member)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void successionUsesDatabaseOrderAndCurrentOwnerRatherThanCreator(boolean tied) throws Exception {
        UUID room=room(); addMember(room);
        members.saveAndFlush(new MeetingMember(room,outsider,RoomRole.MEMBER));
        UUID first=UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID second=UUID.fromString("80000000-0000-0000-0000-000000000000");
        jdbc.update("update meeting_members set id=?,joined_at='2026-01-01T00:00:00Z' where room_id=? and user_id=?",first,room,member);
        jdbc.update("update meeting_members set id=?,joined_at=?::timestamptz where room_id=? and user_id=?",second,tied?"2026-01-01T00:00:00Z":"2026-01-02T00:00:00Z",room,outsider);
        mvc.perform(auth(delete(departurePath(room)),owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.newOwnerId").value(member.toString()));
        assertThat(rooms.findById(room).orElseThrow().getCreatedBy()).isEqualTo(owner);
        mvc.perform(auth(get("/api/v1/rooms/"+room+"/invite-code"),member)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/rooms/"+room+"/invite-code"),owner)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/rooms/"+room),member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerId").value(member.toString()));
        assertThat(leaving.leave(room,member).newOwnerId()).isEqualTo(outsider);
        assertThat(rooms.findById(room).orElseThrow().getCreatedBy()).isEqualTo(owner);
        assertThat(members.findCurrentRole(room,outsider)).contains(RoomRole.OWNER);
    }

    private List<UUID> records(UUID room, UUID author) {
        UUID run=UUID.randomUUID(),result=UUID.randomUUID(),proposal=UUID.randomUUID();
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')",run,room,author);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')",result,run,station());
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (?,?,?,?,?)",proposal,room,station(),author,result);
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)",UUID.randomUUID(),room,proposal,author);
        jdbc.update("insert into room_messages(id,room_id,sender_id,sender_type,content) values (?,?,?,'USER','history')",UUID.randomUUID(),room,author);
        return List.of(run,result,proposal);
    }

    @Test void votesCascadeHistorySurvivesThenLastLeaveCascadesOnlyItsRoom() throws Exception {
        UUID room=room(); addMember(room); records(room,owner);
        UUID other=room(); records(other,owner);
        Map<String,List<Map<String,Object>>> historyBefore=new HashMap<>();
        for(String table:List.of("recommendation_runs","recommendation_results","station_proposals","room_messages"))
            historyBefore.put(table,jdbc.queryForList("select * from "+table+(table.equals("room_messages")?" where sender_type<>'SYSTEM'":"")+" order by id"));
        Map<String,List<Map<String,Object>>> otherBefore=new HashMap<>();
        for(String table:List.of("meeting_rooms","meeting_members","recommendation_runs","station_proposals","station_proposal_votes","room_messages")) {
            otherBefore.put(table,jdbc.queryForList("select * from "+table+" where "+(table.equals("meeting_rooms")?"id":"room_id")+"=?",other));
        }
        leaving.leave(room,owner);
        // SYSTEM messages (#76) are new rows by design; the history that must stay is everything else.
        historyBefore.forEach((table,before)->assertThat(jdbc.queryForList("select * from "+table+(table.equals("room_messages")?" where sender_type<>'SYSTEM'":"")+" order by id")).isEqualTo(before));
        assertThat(jdbc.queryForObject("select count(*) from station_proposal_votes where room_id=?",Long.class,room)).isZero();
        for(String table:List.of("recommendation_runs","station_proposals","room_messages"))
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where room_id=?"+(table.equals("room_messages")?" and sender_type<>'SYSTEM'":""),Long.class,room)).isEqualTo(1);
        assertThat(count("recommendation_results")).isEqualTo(2);
        mvc.perform(auth(delete(departurePath(room)),member)).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":{\"roomDeleted\":true,\"newOwnerId\":null}}"));
        otherBefore.forEach((table,before)->assertThat(jdbc.queryForList("select * from "+table+" where "+(table.equals("meeting_rooms")?"id":"room_id")+"=?",other)).isEqualTo(before));
        for(String table:List.of("recommendation_results","recommendation_runs","station_proposals","station_proposal_votes","room_messages")) assertThat(count(table)).isEqualTo(1);
        assertThat(users.count()).isEqualTo(3);
        mvc.perform(auth(delete(departurePath(room)),member)).andExpect(status().isNotFound());
    }

    @Test void errorsAndAuthentication() throws Exception {
        UUID room=room();
        mvc.perform(delete(departurePath(room))).andExpect(status().isUnauthorized());
        mvc.perform(auth(delete(departurePath(room)),outsider)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("NOT_ROOM_MEMBER"));
        mvc.perform(auth(delete(departurePath(UUID.randomUUID())),owner)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROOM_NOT_FOUND"));
        mvc.perform(auth(delete("/api/v1/rooms/invalid/members/me"),owner)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    private void awaitWaiters(int expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        List<Integer> pids=List.of();
        while(System.nanoTime()<deadline) {
            // A caller may hold its transaction open: invalidate cached statistics each poll.
            jdbc.execute("select pg_stat_clear_snapshot()");
            pids=jdbc.queryForList("select pid from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%meeting_rooms%' and pid<>pg_backend_pid()",Integer.class);
            if(pids.size()==expected) break;
            Thread.sleep(10);
        }
        assertThat(pids).hasSize(expected).doesNotHaveDuplicates();
    }

    @Test void simultaneousOwnerAndSuccessorLeaveRetainsExactlyOneOwner() throws Exception {
        UUID room=room(); addMember(room); members.saveAndFlush(new MeetingMember(room,outsider,RoomRole.MEMBER));
        jdbc.update("update meeting_members set joined_at='2026-01-01T00:00:00Z' where room_id=? and user_id=?",room,member);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1),ready=new CountDownLatch(2);
        try(var holder=POSTGRES.createConnection("")) {
            holder.setAutoCommit(false);
            try(var q=holder.prepareStatement("select id from meeting_rooms where id=? for no key update")) { q.setObject(1,room); q.executeQuery().close(); }
            List<Future<?>> futures=new ArrayList<>();
            for(UUID user:List.of(owner,member)) futures.add(pool.submit(()-> { ready.countDown(); assertThat(start.await(10,TimeUnit.SECONDS)).isTrue(); return leaving.leave(room,user); }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown(); awaitWaiters(2); holder.commit();
            for(var f:futures) f.get(10,TimeUnit.SECONDS);
            assertThat(members.countByRoomId(room)).isEqualTo(1);
            assertThat(members.findCurrentRole(room,outsider)).contains(RoomRole.OWNER);
            assertThat(jdbc.queryForObject("select count(*) from meeting_members where room_id=? and role='OWNER'",Long.class,room)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lastLeaveVersusJoinUsesConsistentLockOrder(boolean leaveFirst) throws Exception {
        UUID room=room(); ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx->{
                access.lockMember(room,owner);
                Future<String> waiter=pool.submit(()->{
                    try {if(leaveFirst) joining.join(member,joinRequest()); else leaving.leave(room,owner); return "OK";}
                    catch(BusinessException e){return e.getErrorCode().name();}
                });
                pending=waiter;
                try {awaitWaiters(1);} catch(Exception e){throw new AssertionError(e);}
                if(leaveFirst) processor.leaveLocked(rooms.findById(room).orElseThrow(),owner);
                else membership.join(member,"A00001");
            });
            assertThat(pending.get(10,TimeUnit.SECONDS)).isEqualTo(leaveFirst?"ROOM_CODE_INVALID":"OK");
            assertThat(rooms.existsById(room)).isEqualTo(!leaveFirst);
            assertThat(members.countByRoomId(room)).isEqualTo(leaveFirst?0:1);
            if(!leaveFirst) assertThat(members.findCurrentRole(room,member)).contains(RoomRole.OWNER);
        } finally {pool.shutdownNow();}
    }
    private Future<String> pending;

    @Test void lockTimeoutReturnsBusyWithoutDeletingAnything() throws Exception {
        UUID room=room();
        try(var holder=POSTGRES.createConnection("")) {
            holder.setAutoCommit(false);
            try(var q=holder.prepareStatement("select id from meeting_rooms where id=? for no key update")) {q.setObject(1,room);q.executeQuery().close();}
            long start=System.nanoTime();
            mvc.perform(auth(delete(departurePath(room)),owner)).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_BUSY"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isBetween(2500L,6000L);
            holder.rollback();
        }
        assertThat(members.findCurrentRole(room,owner)).contains(RoomRole.OWNER);
        assertThat(rooms.existsById(room)).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void failureDuringPromotionOrRoomDeletionRollsBackCascade(boolean last) {
        UUID room=room(); if(!last) addMember(room); records(room,owner);
        List<Map<String,Object>> before=jdbc.queryForList("select * from meeting_members where room_id=? order by id",room);
        String table=last?"meeting_rooms":"meeting_members";
        jdbc.execute("create function leave_failure() returns trigger language plpgsql as $$ begin raise exception 'injected'; end $$");
        jdbc.execute("create trigger leave_failure before "+(last?"delete":"update")+" on "+table+" for each row execute function leave_failure()");
        try {assertThatThrownBy(()->leaving.leave(room,owner)).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("drop trigger leave_failure on "+table);jdbc.execute("drop function leave_failure()");}
        assertThat(jdbc.queryForList("select * from meeting_members where room_id=? order by id",room)).isEqualTo(before);
        assertThat(rooms.existsById(room)).isTrue();
        for(String t:List.of("recommendation_runs","recommendation_results","station_proposals","station_proposal_votes","room_messages")) assertThat(count(t)).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void allLocksFirstAllowsAtomicMultiRoomReuseAndOuterRollback(boolean rollback) {
        UUID first=room(),second=room();
        List<UUID> ids=jdbc.queryForList("select id from meeting_rooms order by id",UUID.class);
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            List<MeetingRoom> locked=ids.stream().map(id->access.lockMember(id,owner)).toList();
            for(var r:locked) processor.leaveLocked(r,owner);
            members.flush();
            if(rollback) tx.setRollbackOnly();
        });
        for(UUID id:List.of(first,second)) {
            assertThat(rooms.existsById(id)).isEqualTo(rollback);
            assertThat(members.findCurrentRole(id,owner)).isEqualTo(rollback?Optional.of(RoomRole.OWNER):Optional.empty());
        }
    }

    @Test void openApiMatchesRuntimeContractAndResolvesSchemas() throws Exception {
        var spec=mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var op=spec.at("/paths/~1api~1v1~1rooms~1{roomId}~1members~1me/delete");
        assertThat(op.path("security").toString()).contains("bearerAuth");
        assertThat(op.path("parameters").size()).isEqualTo(1);
        var param=op.at("/parameters/0");
        assertThat(param.path("name").asString()).isEqualTo("roomId");
        assertThat(param.path("required").asBoolean()).isTrue();
        assertThat(param.at("/schema/format").asString()).isEqualTo("uuid");
        for(String code:List.of("200","400","401","403","404","409","500")) {
            String ref=op.at("/responses/"+code+"/content/application~1json/schema/$ref").asString();
            assertThat(ref).startsWith("#/components/schemas/");
            assertThat(spec.at(ref.substring(1)).isMissingNode()).isFalse();
        }
        assertThat(spec.at("/components/schemas/RoomLeaveResponse/properties/data/$ref").asString()).isEqualTo("#/components/schemas/RoomLeftData");
        var data=spec.at("/components/schemas/RoomLeftData");
        assertThat(data.path("required").toString()).contains("roomDeleted","newOwnerId");
        assertThat(data.at("/properties/newOwnerId/type").toString()).contains("string","null");
        assertThat(data.at("/properties/newOwnerId/format").asString()).isEqualTo("uuid");
        assertThat(op.at("/responses/409/content/application~1json/examples/ROOM_BUSY/value/error/code").asString()).isEqualTo("ROOM_BUSY");
    }
}

