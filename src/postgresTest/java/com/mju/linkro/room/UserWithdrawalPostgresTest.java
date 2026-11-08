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
class UserWithdrawalPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired UserRepository users;
    @Autowired MeetingRoomRepository rooms;
    @Autowired MeetingMemberRepository members;
    @Autowired com.mju.linkro.user.service.UserService withdrawal;
    @MockitoSpyBean com.mju.linkro.user.service.UserAccess userAccess;
    @MockitoSpyBean RoomLeaveProcessor processor;
    @MockitoSpyBean RoomAccess access;
    @Autowired RoomCreationAttempt creation;
    @Autowired RoomMembershipService membership;
    @Autowired RoomLeaveService leaving;
    @Autowired com.mju.linkro.auth.service.RefreshTokenService tokens;
    @Autowired com.mju.linkro.auth.repository.RefreshTokenRepository refreshTokens;
    @Autowired com.mju.linkro.auth.service.UserLoginService login;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    UUID target, other;
    AtomicInteger codes;

    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms");
        jdbc.update("delete from refresh_tokens");
        jdbc.update("delete from users");
        target=users.saveAndFlush(new User("target","target")).getId();
        other=users.saveAndFlush(new User("other","other")).getId();
        codes=new AtomicInteger();
    }
    UUID room(UUID owner) {
        return creation.create(owner,String.format("W%05d",codes.incrementAndGet()),"room",null).id();
    }
    void add(UUID room, UUID user) { members.saveAndFlush(new MeetingMember(room,user,RoomRole.MEMBER)); }
    void assertGone() {
        assertThat(users.findById(target).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(members.findRoomIdsForWithdrawal(target)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id=? and revoked_at is null",Long.class,target)).isZero();
    }
    @Test void oldAccessAndRefreshTokensAreRejectedByHttpAfterWithdrawal() throws Exception {
        UUID owned=room(target); add(owned,other);
        String accessToken=jwt.createAccessToken(target);
        String raw=tokens.issue(target,UUID.randomUUID());
        MockMvc mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(delete("/api/v1/users/me").header("Authorization","Bearer "+accessToken))
            .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":null}"));
        mvc.perform(get("/api/v1/rooms").header("Authorization","Bearer "+accessToken))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        mvc.perform(post("/api/v1/auth/refresh").contentType("application/json")
            .content("{\"refreshToken\":\""+raw+"\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));
        assertGone();
        login.loginExisting("target");
        assertThat(members.findRoomIdsForWithdrawal(target)).isEmpty();
        assertThat(members.findCurrentRole(owned,other)).contains(RoomRole.OWNER);
    }
    @Test void preloadedActiveEntityIsRefreshedAfterConcurrentWithdrawal() {
        new TransactionTemplate(manager).executeWithoutResult(status->{
            assertThat(users.findById(target).orElseThrow().getDeletedAt()).isNull();
            ExecutorService pool=Executors.newSingleThreadExecutor();
            try { pool.submit(()->withdrawal.withdraw(target)).get(10,TimeUnit.SECONDS); }
            catch(Exception failure) { throw new AssertionError(failure); }
            finally { pool.shutdownNow(); }
            assertThatThrownBy(()->userAccess.lockActive(target)).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID);
            status.setRollbackOnly();
        });
        assertGone();
    }
    @Test void noRoomsRevokesAllTokensAndExplicitLoginRestoresOnlyAccount() {
        String raw=tokens.issue(target,UUID.randomUUID());
        tokens.issue(target,UUID.randomUUID());
        withdrawal.withdraw(target); assertGone();
        assertThat(tokens.rotate(raw).error()).isEqualTo(ErrorCode.TOKEN_INVALID);
        assertThatThrownBy(()->creation.create(target,"X00001","room",null)).isInstanceOf(BusinessException.class);
        login.loginExisting("target");
        assertThat(users.findById(target).orElseThrow().getDeletedAt()).isNull();
        assertThat(members.findRoomIdsForWithdrawal(target)).isEmpty();
        assertThat(tokens.rotate(raw).error()).isEqualTo(ErrorCode.TOKEN_INVALID);
    }
    @Test void mixedRolesSuccessionAndCascadePreserveHistory() {
        UUID owned=room(target); add(owned,other);
        UUID joined=room(other); add(joined,target);
        UUID last=room(target);
        long station=jdbc.queryForObject("select min(id) from stations",Long.class);
        UUID proposal=UUID.randomUUID();
        jdbc.update("insert into station_proposals(id,room_id,station_id,proposed_by) values (?,?,?,?)",proposal,owned,station,target);
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)",UUID.randomUUID(),owned,proposal,target);
        UUID run=UUID.randomUUID();
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')",run,owned,target);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')",UUID.randomUUID(),run,station);
        jdbc.update("insert into room_messages(id,room_id,sender_id,sender_type,content) values (?,?,?,'USER','history')",UUID.randomUUID(),owned,target);
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')",UUID.randomUUID(),last,target);
        jdbc.update("insert into room_messages(id,room_id,sender_id,sender_type,content) values (?,?,?,'USER','history')",UUID.randomUUID(),last,target);
        withdrawal.withdraw(target); assertGone();
        assertThat(jdbc.queryForObject("select count(*) from recommendation_runs",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from recommendation_results",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from room_messages",Long.class)).isEqualTo(1);
        assertThat(rooms.findById(last)).isEmpty();
        assertThat(members.findCurrentRole(owned,other)).contains(RoomRole.OWNER);
        assertThat(members.findCurrentRole(joined,other)).contains(RoomRole.OWNER);
        assertThat(rooms.findById(owned).orElseThrow().getCreatedBy()).isEqualTo(target);
        assertThat(jdbc.queryForObject("select count(*) from station_proposal_votes",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from station_proposals",Long.class)).isEqualTo(1);
    }
    @Test void failureInSecondRoomRollsBackEveryChange() {
        UUID a=room(target); add(a,other); UUID b=room(target);
        tokens.issue(target,UUID.randomUUID());
        AtomicInteger calls=new AtomicInteger();
        doAnswer(call->{ if(calls.incrementAndGet()==2) throw new IllegalStateException("injected"); return call.callRealMethod(); })
                .when(org.springframework.test.util.AopTestUtils.<RoomLeaveProcessor>getUltimateTargetObject(processor)).leaveLocked(any(),eq(target));
        assertThatThrownBy(()->withdrawal.withdraw(target)).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(target).orElseThrow().getDeletedAt()).isNull();
        assertThat(members.findRoomIdsForWithdrawal(target)).containsExactlyInAnyOrder(a,b);
        assertThat(members.findCurrentRole(a,target)).contains(RoomRole.OWNER);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null",Long.class)).isEqualTo(1);
    }
    @Test void failureAfterTokenRevocationRollsBackRoomsAccountAndTokens() {
        UUID a=room(target); add(a,other); UUID b=room(target);
        tokens.issue(target,UUID.randomUUID());
        var transaction=new TransactionTemplate(manager);
        assertThatThrownBy(()->transaction.executeWithoutResult(status->{
            withdrawal.withdraw(target);
            assertThat(jdbc.queryForObject("select deleted_at is not null from users where id=?",Boolean.class,target)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null",Long.class)).isZero();
            throw new IllegalStateException("failure after all writes");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(target).orElseThrow().getDeletedAt()).isNull();
        assertThat(members.findRoomIdsForWithdrawal(target)).containsExactlyInAnyOrder(a,b);
        assertThat(members.findCurrentRole(a,target)).contains(RoomRole.OWNER);
        assertThat(members.findCurrentRole(a,other)).contains(RoomRole.MEMBER);
        assertThat(rooms.findById(b)).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where revoked_at is null",Long.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"create","join","refresh","nickname"})
    void withdrawalFirstRejectsWaitingWriter(String operation) throws Exception { race(operation,true); }
    @ParameterizedTest @ValueSource(strings={"create","join","refresh","nickname"})
    void writerFirstIsIncludedInWithdrawal(String operation) throws Exception { race(operation,false); }

    void race(String operation, boolean withdrawFirst) throws Exception {
        UUID existing=room(other);
        String code=rooms.findById(existing).orElseThrow().getRoomCode();
        String raw=tokens.issue(target,UUID.randomUUID());
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean armed=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call->{
            Object value=call.callRealMethod();
            if(Thread.currentThread().getName().equals("withdrawal-first") && armed.compareAndSet(true,false)) {
                locked.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();
            }
            return value;
        }).when(org.springframework.test.util.AopTestUtils.<com.mju.linkro.user.service.UserAccess>getUltimateTargetObject(userAccess)).lock(target);
        Runnable writer=()->{
            switch(operation) {
                case "create" -> room(target);
                case "join" -> membership.join(target,code);
                case "refresh" -> tokens.rotate(raw);
                case "nickname" -> withdrawal.updateNickname(target,"renamed");
            }
        };
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> first=pool.submit(()-> { Thread.currentThread().setName("withdrawal-first");
                if(withdrawFirst) withdrawal.withdraw(target); else writer.run(); });
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> second=pool.submit(()->{
                if(withdrawFirst) {
                    if(operation.equals("refresh")) assertThat(tokens.rotate(raw).error()).isEqualTo(ErrorCode.TOKEN_INVALID);
                    else assertThatThrownBy(writer::run).isInstanceOf(BusinessException.class)
                            .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID);
                } else withdrawal.withdraw(target);
            });
            awaitDatabaseWait(); release.countDown();
            first.get(15,TimeUnit.SECONDS); second.get(15,TimeUnit.SECONDS); assertGone();
            assertThat(members.findCurrentRole(existing,other)).contains(RoomRole.OWNER);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    void awaitDatabaseWait() {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'",Long.class)>0) return;
            Thread.yield();
        }
        throw new AssertionError("Second connection never waited for database lock");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void roomOrMembershipDisappearingAfterListReadDoesNotFailWithdrawal(boolean lastMember) {
        UUID last=room(target);
        if(!lastMember) add(last,other);
        doAnswer(call->{ var pool=Executors.newSingleThreadExecutor();
            try { pool.submit(()->leaving.leave(last,target)).get(10,TimeUnit.SECONDS); }
            finally { pool.shutdownNow(); }
            return call.callRealMethod(); })
            .when(org.springframework.test.util.AopTestUtils.<RoomAccess>getUltimateTargetObject(access)).lockForWithdrawal(last);
        withdrawal.withdraw(target); assertGone();
        assertThat(rooms.findById(last).isEmpty()).isEqualTo(lastMember);
        if(!lastMember) assertThat(members.findCurrentRole(last,other)).contains(RoomRole.OWNER);
    }
    @Test void overlappingWithdrawalsUseDatabaseRoomOrder() throws Exception {
        UUID a=room(target); add(a,other);
        UUID b=room(other); add(b,target);
        CyclicBarrier barrier=new CyclicBarrier(2);
        ThreadLocal<Boolean> first=ThreadLocal.withInitial(()->true);
        doAnswer(call->{
            if(first.get()) { first.set(false); barrier.await(10,TimeUnit.SECONDS); }
            return call.callRealMethod();
        }).when(org.springframework.test.util.AopTestUtils.<RoomAccess>getUltimateTargetObject(access)).lockForWithdrawal(any());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> one=pool.submit(()->withdrawal.withdraw(target));
            Future<?> two=pool.submit(()->withdrawal.withdraw(other));
            one.get(15,TimeUnit.SECONDS); two.get(15,TimeUnit.SECONDS);
            assertGone();
            assertThat(users.findById(other).orElseThrow().getDeletedAt()).isNotNull();
            assertThat(rooms.findAll()).isEmpty(); assertThat(members.findAll()).isEmpty();
        } finally { pool.shutdownNow(); }
    }

}
