package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.sql.*;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(45)
class SelectedStationPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired SelectedStationService service;
    @Autowired RoomService reads;
    @Autowired RoomCreationAttempt creation;
    @Autowired RoomLeaveService leaving;
    @Autowired RoomAccess access;
    @Autowired UserRepository users;
    @Autowired MeetingMemberRepository members;
    @Autowired MeetingRoomRepository rooms;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    MockMvc mvc;
    UUID owner, member, outsider, room;
    long first, second, unsupported;
    final JsonMapper mapper=JsonMapper.builder().build();

    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
        owner=users.saveAndFlush(new User("owner","owner")).getId();
        member=users.saveAndFlush(new User("member","member")).getId();
        outsider=users.saveAndFlush(new User("outsider","outsider")).getId();
        room=creation.create(owner,"S00001","room",Instant.parse("2026-12-01T12:00:00Z")).id();
        members.saveAndFlush(new MeetingMember(room,member,RoomRole.MEMBER));
        var stations=jdbc.queryForList("select id from stations where is_supported order by id limit 2",Long.class);
        first=stations.get(0); second=stations.get(1);
        unsupported=jdbc.queryForObject("insert into stations(name,latitude,longitude,is_supported) values ('unsupported',0,0,false) returning id",Long.class);
    }
    SelectedStationRequest request(long id) { return mapper.readValue("{\"stationId\":"+id+"}",SelectedStationRequest.class); }
    void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(code);
    }
    List<String> history() {
        return new TransactionTemplate(manager).execute(status->{
            var result=new ArrayList<String>();
            for(String table:List.of("meeting_members","station_proposals","station_proposal_votes","recommendation_runs","recommendation_results","room_messages"))
                result.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by id",String.class));
            return result;
        });
    }
    void seedHistory() {
        UUID run=UUID.randomUUID(), result=UUID.randomUUID(), proposal=UUID.randomUUID();
        jdbc.update("update meeting_members set departure_station_id=? where room_id=?",first,room);
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')",run,room,owner);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')",result,run,first);
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by,source_result_id) values (?,?,?,?,?)",proposal,room,first,owner,result);
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)",UUID.randomUUID(),room,proposal,member);
        jdbc.update("insert into room_messages(id,room_id,sender_id,sender_type,content) values (?,?,?,'USER','history')",UUID.randomUUID(),room,owner);
    }

    @Test void selectsChangesAndReconfirmsWithoutClearingAnyHistory() {
        seedHistory(); var before=history();
        service.select(room,owner,request(second)); // No candidate and no votes for this station.
        assertThat(reads.detail(room,member).selectedStation().id()).isEqualTo(second);
        assertThat(reads.detail(room,member).confirmedAt()).isNotNull();
        assertThat(reads.list(member).get(0).status()).isEqualTo(RoomStatus.CONFIRMED);
        assertThat(reads.list(member).get(0).selectedStation().id()).isEqualTo(second);
        service.select(room,owner,request(first));
        assertThat(reads.detail(room,owner).selectedStation().id()).isEqualTo(first);
        jdbc.update("update meeting_rooms set confirmed_at='2000-01-01T00:00:00Z' where id=?",room);
        service.select(room,owner,request(first));
        assertThat(reads.detail(room,owner).confirmedAt()).isAfter(Instant.parse("2000-01-01T00:00:00Z"));
        assertThat(reads.detail(room,owner).appointmentAt()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));
        assertThat(history()).isEqualTo(before);
    }

    String path() { return "/api/v1/rooms/"+room+"/selected-station"; }
    @Test void httpSelectClearAndRepeatedClearPreserveHistoryAndOpenState() throws Exception {
        seedHistory(); var before=history(); String bearer="Bearer "+jwt.createAccessToken(owner);
        mvc.perform(put(path()).header("Authorization",bearer).contentType("application/json")
                .content("{\"stationId\":"+second+"}"))
            .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":null}"));
        String clearedRow=null;
        for(int i=0;i<3;i++) {
            mvc.perform(delete(path()).header("Authorization",bearer)).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":null}"));
            var detail=reads.detail(room,owner);
            assertThat(detail.selectedStation()).isNull(); assertThat(detail.confirmedAt()).isNull();
            assertThat(detail.status()).isEqualTo(RoomStatus.OPEN);
            assertThat(detail.appointmentAt()).isEqualTo(Instant.parse("2026-12-01T12:00:00Z"));
            String row=jdbc.queryForObject("select row_to_json(t)::text from meeting_rooms t where id=?",String.class,room);
            if(i==0) clearedRow=row;
            else assertThat(row).isEqualTo(clearedRow); // Repeated DELETE does not even change updated_at.
            assertThat(reads.list(owner).get(0).selectedStation()).isNull();
            assertThat(reads.list(owner).get(0).status()).isEqualTo(RoomStatus.OPEN);
            assertThat(history()).isEqualTo(before);
        }
        assertThat(service.clear(room,owner)).isFalse();
        error(()->service.clear(room,member),ErrorCode.NOT_ROOM_OWNER);
        error(()->service.clear(room,outsider),ErrorCode.NOT_ROOM_MEMBER);
        error(()->service.clear(UUID.randomUUID(),owner),ErrorCode.ROOM_NOT_FOUND);
    }

    @Test void clearFailureAfterFlushRollsBackBothFields() {
        service.select(room,owner,request(first)); var before=reads.detail(room,owner);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(service.clear(room,owner)).isTrue(); rooms.flush();
            assertThat(jdbc.queryForObject("select selected_station_id from meeting_rooms where id=?",Long.class,room)).isNull();
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reads.detail(room,owner)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualHttpTimeoutPreservesSelectionForPutAndDelete(boolean clear) throws Exception {
        service.select(room,owner,request(first)); var before=reads.detail(room,owner);
        var pool=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1);
        try(var held=holdRoom()) {
            try {
                String bearer="Bearer "+jwt.createAccessToken(owner);
                var future=pool.submit(()->{
                    entered.countDown();
                    var request=clear?delete(path()):put(path()).contentType("application/json").content("{\"stationId\":"+second+"}");
                    return mvc.perform(request.header("Authorization",bearer)).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("ROOM_BUSY"));
                });
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                int blocker=backend(held); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                boolean observed=false;
                while(System.nanoTime()<deadline) {
                    if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and ?=any(pg_blocking_pids(pid))",Long.class,blocker)>0) { observed=true; break; }
                    Thread.yield();
                }
                assertThat(observed).isTrue(); future.get(25,TimeUnit.SECONDS);
                assertThat(reads.detail(room,owner)).isEqualTo(before);
            } finally { held.rollback(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void concurrentSelectionAndClearSerializeWithLastCommittedWriterWinning(boolean clearFirst) throws Exception {
        service.select(room,owner,request(first));
        var pool=Executors.newFixedThreadPool(2);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1); var entered=new CountDownLatch(1);
        var holderPid=new AtomicInteger(); var waiterPid=new AtomicInteger();
        try {
            var firstWriter=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                access.lockOwner(room,owner); holderPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));
                locked.countDown();
                try { assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                if(clearFirst) service.clear(room,owner); else service.select(room,owner,request(first));
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var secondWriter=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                waiterPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); entered.countDown();
                if(clearFirst) service.select(room,owner,request(second)); else service.clear(room,owner);
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); awaitWait(waiterPid.get(),holderPid.get());
            release.countDown(); firstWriter.get(15,TimeUnit.SECONDS); secondWriter.get(15,TimeUnit.SECONDS);
            var result=reads.detail(room,owner);
            assertThat(result.status()).isEqualTo(clearFirst?RoomStatus.CONFIRMED:RoomStatus.OPEN);
            if(clearFirst) { assertThat(result.selectedStation().id()).isEqualTo(second); assertThat(result.confirmedAt()).isNotNull(); }
            else { assertThat(result.selectedStation()).isNull(); assertThat(result.confirmedAt()).isNull(); }
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void permissionsAndStationErrorsLeavePreviousSelectionUnchanged() {
        service.select(room,owner,request(first));
        var confirmed=reads.detail(room,owner).confirmedAt();
        error(()->service.select(room,member,request(second)),ErrorCode.NOT_ROOM_OWNER);
        error(()->service.select(room,outsider,request(second)),ErrorCode.NOT_ROOM_MEMBER);
        error(()->service.select(UUID.randomUUID(),owner,request(second)),ErrorCode.ROOM_NOT_FOUND);
        error(()->service.select(room,owner,request(Long.MAX_VALUE)),ErrorCode.STATION_NOT_FOUND);
        error(()->service.select(room,owner,request(unsupported)),ErrorCode.STATION_UNSUPPORTED);
        assertThat(reads.detail(room,owner).selectedStation().id()).isEqualTo(first);
        assertThat(reads.detail(room,owner).confirmedAt()).isEqualTo(confirmed);
    }

    @Test void failureAfterMutationRollsBackBothFields() {
        service.select(room,owner,request(first));
        var before=reads.detail(room,owner);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{
            service.select(room,owner,request(second));
            rooms.flush();
            assertThat(jdbc.queryForObject("select selected_station_id from meeting_rooms where id=?",Long.class,room)).isEqualTo(second);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reads.detail(room,owner)).isEqualTo(before);
    }

    Connection holdRoom() throws Exception {
        var connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        try {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select id from meeting_rooms where id=? for update")) {
                statement.setObject(1,room); statement.executeQuery().close();
            }
            return connection;
        } catch(Exception failure) { connection.close(); throw failure; }
    }
    int backend(Connection connection) throws Exception {
        try(var statement=connection.createStatement(); var result=statement.executeQuery("select pg_backend_pid()")) {
            result.next(); return result.getInt(1);
        }
    }
    void awaitWait(int waiter,int blocker) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",Boolean.class,blocker,waiter))) return;
            Thread.yield();
        }
        throw new AssertionError("Request did not wait for held room lock");
    }

    @Test void actualTimeoutRollsBackAndReleasesRoomLock() throws Exception {
        service.select(room,owner,request(first)); var before=reads.detail(room,owner);
        var pool=Executors.newSingleThreadExecutor(); var entered=new CountDownLatch(1); var pid=new AtomicInteger();
        try(var held=holdRoom()) {
            try {
                long started=System.nanoTime();
                var future=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); entered.countDown();
                    service.select(room,owner,request(second));
                }));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); awaitWait(pid.get(),backend(held));
                assertThatThrownBy(()->future.get(25,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(e->assertThat(((BusinessException)e.getCause()).getErrorCode()).isEqualTo(ErrorCode.ROOM_BUSY));
                assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isBetween(2500L,25000L);
                assertThat(reads.detail(room,owner)).isEqualTo(before);
            } finally { held.rollback(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        }
        new TransactionTemplate(manager).executeWithoutResult(status->
            assertThat(jdbc.queryForObject("select id from meeting_rooms where id=? for update nowait",UUID.class,room)).isEqualTo(room));
    }

    @ParameterizedTest @ValueSource(strings={"promoted","departed","deleted"})
    void waitingSelectionChecksOwnerAndExistenceAfterConcurrentDeparture(String outcome) throws Exception {
        UUID requester=outcome.equals("promoted")?member:owner;
        if(outcome.equals("deleted")) leaving.leave(room,member);
        var pool=Executors.newFixedThreadPool(2);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        var entered=new CountDownLatch(1); var pid=new AtomicInteger(); var holderPid=new AtomicInteger();
        try {
                var departure=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                    access.lockMember(room,owner);
                    holderPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); locked.countDown();
                    try { assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    leaving.leave(room,owner);
                }));
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var future=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                    pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); entered.countDown();
                    service.select(room,requester,request(second));
                }));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); awaitWait(pid.get(),holderPid.get());
                release.countDown(); departure.get(15,TimeUnit.SECONDS);
                if(outcome.equals("promoted")) { future.get(15,TimeUnit.SECONDS); assertThat(reads.detail(room,member).selectedStation().id()).isEqualTo(second); }
                else assertThatThrownBy(()->future.get(15,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .satisfies(e->assertThat(((BusinessException)e.getCause()).getErrorCode())
                        .isEqualTo(outcome.equals("deleted")?ErrorCode.ROOM_NOT_FOUND:ErrorCode.NOT_ROOM_MEMBER));
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }
}
