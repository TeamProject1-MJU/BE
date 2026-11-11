package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.user.service.UserService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(60)
class StationProposalPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired StationProposalService service;
    @Autowired RoomCreationAttempt creation;
    @Autowired RoomLeaveService leaving;
    @Autowired SelectedStationService selection;
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired MeetingMemberRepository members;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean RoomAccess access;
    @MockitoSpyBean StationProposalRepository proposals;
    UUID room,owner,member,other;
    List<Long> stations;
    final JsonMapper mapper=JsonMapper.builder().build();
    @BeforeEach void setup() {
        jdbc.update("delete from meeting_rooms"); jdbc.update("delete from refresh_tokens"); jdbc.update("delete from users");
        owner=users.saveAndFlush(new User("owner","방장")).getId();
        member=users.saveAndFlush(new User("member","제안자")).getId();
        other=users.saveAndFlush(new User("other","다른멤버")).getId();
        room=creation.create(owner,"P00001","room",null).id();
        members.saveAndFlush(new MeetingMember(room,member,RoomRole.MEMBER));
        members.saveAndFlush(new MeetingMember(room,other,RoomRole.MEMBER));
        stations=jdbc.queryForList("select id from stations where is_supported order by id limit 12",Long.class);
        assertThat(stations).hasSize(12);
    }
    StationProposalRequest request(long station) { return request(station,null); }
    StationProposalRequest request(long station,UUID result) {
        return mapper.readValue("{\"stationId\":"+station+(result==null?"":",\"resultId\":\""+result+"\"")+"}",StationProposalRequest.class);
    }
    void error(Runnable action,ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(code);
    }
    List<String> concurrent(boolean sameStation) throws Exception {
        var pool=Executors.newFixedThreadPool(12); var ready=new CountDownLatch(12); var start=new CountDownLatch(1);
        try {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<12;i++) {
                long station=stations.get(sameStation?0:i);
                futures.add(pool.submit(()->{
                    ready.countDown(); assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                    try { service.create(room,member,request(station)); return "OK"; }
                    catch(BusinessException e) { return e.getErrorCode().name(); }
                }));
            }
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            var results=new ArrayList<String>(); for(var future:futures) results.add(future.get(30,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }
    @Test void twelveDistinctConcurrentProposalsHaveExactlyNineSuccesses() throws Exception {
        var results=concurrent(false);
        assertThat(Collections.frequency(results,"OK")).isEqualTo(9);
        assertThat(Collections.frequency(results,"PROPOSAL_LIMIT_EXCEEDED")).isEqualTo(3);
        assertThat(service.list(room,owner).items()).hasSize(9);
    }
    @Test void sameStationConcurrentProposalsHaveExactlyOneSuccess() throws Exception {
        var results=concurrent(true);
        assertThat(Collections.frequency(results,"OK")).isEqualTo(1);
        assertThat(Collections.frequency(results,"ALREADY_PROPOSED")).isEqualTo(11);
    }
    UUID result(UUID targetRoom) {
        UUID run=UUID.randomUUID(),result=UUID.randomUUID();
        jdbc.update("insert into recommendation_runs(id,room_id,requested_by,criteria) values (?,?,?,'{}')",run,targetRoom,owner);
        jdbc.update("insert into recommendation_results(id,run_id,criterion,station_id,metric_value,trips,nearby,reason,reason_source) values (?,?,'TIME',?,1,'[]','[]','reason','TEMPLATE')",result,run,stations.get(0));
        return result;
    }
    @Test void resultAndProposalAreRoomScopedButAlternativeStationIsAllowed() {
        UUID another=creation.create(owner,"P00002","other",null).id(), result=result(another);
        error(()->service.create(room,member,request(stations.get(1),result)),ErrorCode.RESULT_NOT_FOUND);
        error(()->service.create(room,member,request(stations.get(1),UUID.randomUUID())),ErrorCode.RESULT_NOT_FOUND);
        var proposal=service.create(another,owner,request(stations.get(1),result));
        assertThat(proposal.resultId()).isEqualTo(result);
        error(()->service.delete(room,owner,proposal.id()),ErrorCode.PROPOSAL_NOT_FOUND);
        assertThat(service.list(another,owner).items()).hasSize(1);
        var own=service.create(room,member,request(stations.get(2),result(room)));
        assertThat(own.resultId()).isNotNull();
    }
    void vote(UUID proposal,UUID user) {
        jdbc.update("insert into station_proposal_votes(id,room_id,proposal_id,user_id) values (?,?,?,?)",UUID.randomUUID(),room,proposal,user);
    }
    @Test void listOrdersByTimestampThenIdAndAggregatesActualVotesAndSelection() {
        var first=service.create(room,member,request(stations.get(0)));
        var second=service.create(room,owner,request(stations.get(1)));
        selection.select(room,owner,mapper.readValue("{\"stationId\":"+stations.get(0)+"}",SelectedStationRequest.class));
        vote(first.id(),owner); vote(first.id(),member); vote(second.id(),other);
        var list=service.list(room,owner);
        assertThat(list.maxProposals()).isEqualTo(9); assertThat(list.selectedStation().id()).isEqualTo(stations.get(0));
        assertThat(list.items()).extracting(i->i.id()).containsExactly(second.id(),first.id());
        assertThat(list.items().get(0).isSelected()).isFalse();
        assertThat(list.items().get(0).voteCount()).isEqualTo(1); assertThat(list.items().get(0).votedByMe()).isFalse();
        assertThat(list.items().get(1).isSelected()).isTrue();
        assertThat(list.items().get(1).voteCount()).isEqualTo(2); assertThat(list.items().get(1).votedByMe()).isTrue();
        jdbc.update("update station_proposals set created_at='2026-01-01T00:00:00Z' where room_id=?",room);
        var ids=jdbc.queryForList("select id from station_proposals where room_id=? order by id desc",UUID.class,room);
        assertThat(service.list(room,owner).items()).extracting(i->i.id()).containsExactlyElementsOf(ids);
    }
    @Test void deletePermissionsCascadeVotesAndPreserveSelectedStation() {
        var proposal=service.create(room,member,request(stations.get(0))); vote(proposal.id(),owner);
        assertThat(proposal.resultId()).isNull();
        selection.select(room,owner,mapper.readValue("{\"stationId\":"+stations.get(0)+"}",SelectedStationRequest.class));
        var before=service.list(room,owner).selectedStation();
        var confirmedAt=jdbc.queryForObject("select confirmed_at from meeting_rooms where id=?",Instant.class,room);
        error(()->service.delete(room,other,proposal.id()),ErrorCode.NOT_PROPOSAL_OWNER);
        service.delete(room,member,proposal.id());
        assertThat(jdbc.queryForObject("select count(*) from station_proposal_votes",Long.class)).isZero();
        assertThat(service.list(room,owner).selectedStation()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select confirmed_at from meeting_rooms where id=?",Instant.class,room)).isEqualTo(confirmedAt);
        var next=service.create(room,member,request(stations.get(1))); service.delete(room,owner,next.id());
        assertThat(service.list(room,owner).items()).isEmpty();
    }
    @Test void leavingAndWithdrawalPreserveProposalsAndStoredNickname() {
        var first=service.create(room,member,request(stations.get(0)));
        leaving.leave(room,member);
        assertThat(service.list(room,owner).items()).extracting(i->i.id()).contains(first.id());
        var second=service.create(room,other,request(stations.get(1)));
        userService.withdraw(other);
        var items=service.list(room,owner).items();
        assertThat(items).hasSize(2);
        assertThat(items.stream().filter(i->i.id().equals(second.id())).findFirst().orElseThrow().proposedBy().nickname()).isEqualTo("다른멤버");
        assertThat(users.findById(other).orElseThrow().getDeletedAt()).isNotNull();
    }
    @Test void roomDeletionCascadesProposalsAndVotes() {
        var proposal=service.create(room,member,request(stations.get(0))); vote(proposal.id(),owner);
        jdbc.update("delete from meeting_rooms where id=?",room);
        assertThat(jdbc.queryForObject("select count(*) from station_proposals",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from station_proposal_votes",Long.class)).isZero();
    }

    @Test void actualUniqueInsertFailureIsTranslatedAndTransactionRollsBack() {
        var created=service.create(room,member,request(stations.get(0)));
        doReturn(false).when(proposals).exists(room,stations.get(0));
        error(()->service.create(room,member,request(stations.get(0))),ErrorCode.ALREADY_PROPOSED);
        assertThat(service.list(room,owner).items()).extracting(i->i.id()).containsExactly(created.id());
        assertThat(service.create(room,member,request(stations.get(1))).id()).isNotNull();
    }

    @Test void listHasIndependentRepeatableReadSnapshotAcrossConcurrentChanges() throws Exception {
        var first=service.create(room,member,request(stations.get(0)));
        var once=new AtomicBoolean(); var pool=Executors.newSingleThreadExecutor();
        doAnswer(invocation->{
            var entity=invocation.callRealMethod();
            assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
            if(once.compareAndSet(false,true)) pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(status->{
                service.create(room,member,request(stations.get(1)));
                // Selection obtains the same room lock; JDBC proposal inserts do not dirty JPA state.
                selection.select(room,owner,mapper.readValue("{\"stationId\":"+stations.get(0)+"}",SelectedStationRequest.class));
                vote(first.id(),owner);
            })).get(15,TimeUnit.SECONDS);
            return entity;
        }).when(access).requireMember(room,owner);
        try {
            new TransactionTemplate(manager).executeWithoutResult(status->{
                assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("read committed");
                var old=service.list(room,owner);
                assertThat(old.selectedStation()).isNull(); assertThat(old.items()).hasSize(1);
                assertThat(old.items().get(0).voteCount()).isZero(); assertThat(old.items().get(0).votedByMe()).isFalse();
                assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("read committed");
            });
            var current=service.list(room,owner);
            assertThat(current.selectedStation().id()).isEqualTo(stations.get(0)); assertThat(current.items()).hasSize(2);
            assertThat(current.items().stream().filter(i->i.id().equals(first.id())).findFirst().orElseThrow().voteCount()).isEqualTo(1);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void actualRoomLockTimeoutIsSeparateFromConcurrencyLimitTest() throws Exception {
        var pool=Executors.newSingleThreadExecutor();
        try(var connection=java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("select id from meeting_rooms where id=? for update")) {
                statement.setObject(1,room); statement.executeQuery().close();
            }
            try {
                var future=pool.submit(()->{
                    error(()->service.create(room,member,request(stations.get(0))),ErrorCode.ROOM_BUSY);
                    error(()->service.delete(room,owner,UUID.randomUUID()),ErrorCode.ROOM_BUSY);
                });
                future.get(20,TimeUnit.SECONDS);
                assertThat(service.list(room,owner).items()).isEmpty();
            } finally { connection.rollback(); }
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(service.create(room,member,request(stations.get(0))).id()).isNotNull();
    }
}
