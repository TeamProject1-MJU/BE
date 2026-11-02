package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.user.domain.User;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "spring.config.import=", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false",
        "spring.datasource.hikari.maximum-pool-size=10",
        "auth.kakao.app-id=1234", "auth.jwt.access-token-ttl=15m",
        "auth.jwt.secret=dGVzdC1vbmx5LXNlY3JldC0wMTIzNDU2Nzg5YWJjZGVmZ2hpams="})
@Timeout(25)
class RoomDomainPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @Autowired MeetingRoomRepository rooms;
    @Autowired MeetingMemberRepository members;
    @Autowired RoomAccess access;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    UUID creator, owner, member, outsider, room, otherRoom;
    ExecutorService pool;

    <T> T tx(Supplier<T> work) { return new TransactionTemplate(manager).execute(status -> work.get()); }

    @BeforeEach void fixtures() {
        pool = Executors.newFixedThreadPool(3);
        tx(() -> {
            jdbc.execute("truncate users cascade");
            creator = user("creator"); owner = user("owner"); member = user("member"); outsider = user("outsider");
            room = rooms.save(new MeetingRoom("ABC123", "room", creator)).getId();
            otherRoom = rooms.save(new MeetingRoom("DEF456", "other", creator)).getId();
            members.save(new MeetingMember(room, owner, RoomRole.OWNER));
            members.save(new MeetingMember(room, member, RoomRole.MEMBER));
            return null;
        });
    }
    UUID user(String name) {
        User user = new User(name, name); em.persist(user); return user.getId();
    }
    @AfterEach void shutdown() throws Exception {
        pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void mappingAuditingAndPersistable() {
        tx(() -> {
            MeetingRoom fresh = new MeetingRoom("GHI789", "fresh", creator);
            assertThat(fresh.isNew()).isTrue(); assertThat(fresh.getId().version()).isEqualTo(7);
            assertThat(rooms.saveAndFlush(fresh)).isSameAs(fresh);
            assertThat(fresh.isNew()).isFalse();
            assertThat(fresh.getCreatedAt()).isNotNull(); assertThat(fresh.getUpdatedAt()).isNotNull();
            assertThat(fresh.getStatus()).isEqualTo(RoomStatus.OPEN);
            assertThat(fresh.getMaxMembers()).isEqualTo(4);
            MeetingMember joined = new MeetingMember(fresh.getId(), member, RoomRole.MEMBER);
            assertThat(members.saveAndFlush(joined)).isSameAs(joined);
            assertThat(joined.isNew()).isFalse(); assertThat(joined.getId().version()).isEqualTo(7);
            assertThat(joined.getJoinedAt()).isNotNull();
            assertThat(jdbc.queryForObject("select role from meeting_members where id = ?", String.class, joined.getId()))
                    .isEqualTo("MEMBER");
            long station = jdbc.queryForObject("insert into stations(name,latitude,longitude) values ('test',37,127) returning id", Long.class);
            Instant time = Instant.parse("2026-11-02T01:02:03.123456Z");
            fresh.schedule(time); fresh.confirmStation(station, time); em.flush(); em.clear();
            MeetingRoom loaded = rooms.findById(fresh.getId()).orElseThrow();
            assertThat(loaded.isNew()).isFalse(); assertThat(loaded.getAppointmentAt()).isEqualTo(time);
            assertThat(loaded.getConfirmedAt()).isEqualTo(time); assertThat(loaded.getSelectedStationId()).isEqualTo(station);
            assertThat(loaded.getStatus()).isEqualTo(RoomStatus.CONFIRMED);
            assertThat(loaded.getUpdatedAt()).isAfterOrEqualTo(loaded.getCreatedAt());
            MeetingMember reloaded = members.findById(joined.getId()).orElseThrow();
            assertThat(reloaded.getDepartureStationId()).isNull(); assertThat(reloaded.getJoinedAt()).isNotNull();
            assertThat(reloaded.isNew()).isFalse();
            loaded.clearSelectedStation(); loaded.schedule(null); em.flush(); em.clear();
            loaded = rooms.findById(fresh.getId()).orElseThrow();
            assertThat(loaded.getConfirmedAt()).isNull(); assertThat(loaded.getAppointmentAt()).isNull();
            assertThat(loaded.getStatus()).isEqualTo(RoomStatus.OPEN);
            return null;
        });
    }

    @Test void permissionsAndErrors() {
        assertThat(access.requireOwner(room, owner).getCreatedBy()).isEqualTo(creator);
        access.requireMember(room, member);
        fails(ErrorCode.NOT_ROOM_OWNER, () -> access.requireOwner(room, member));
        fails(ErrorCode.NOT_ROOM_MEMBER, () -> access.requireMember(room, outsider));
        fails(ErrorCode.NOT_ROOM_MEMBER, () -> access.requireOwner(room, creator));
        fails(ErrorCode.ROOM_NOT_FOUND, () -> access.requireMember(UUID.randomUUID(), outsider));
        fails(ErrorCode.ROOM_NOT_FOUND, () -> tx(() -> access.lockMember(UUID.randomUUID(), owner)));
        fails(ErrorCode.ROOM_CODE_INVALID, () -> tx(() -> access.lockByCode("XXXXXX")));
        fails(ErrorCode.ROOM_CODE_INVALID, () -> tx(() -> access.lockByCode(null)));
        tx(() -> access.lockByCode("ABC123"));
        assertThat(members.countByRoomId(room)).isEqualTo(2);
    }

    @Test void invalidTransactionUsageIsRejected() {
        assertThatThrownBy(() -> access.lockOwner(room, owner)).isInstanceOf(IllegalTransactionStateException.class);
        TransactionTemplate read = new TransactionTemplate(manager); read.setReadOnly(true);
        assertThatThrownBy(() -> read.execute(s -> access.lockOwner(room, owner))).isInstanceOf(IllegalStateException.class);
        TransactionTemplate repeatable = new TransactionTemplate(manager);
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(s -> access.lockOwner(room, owner))).isInstanceOf(IllegalStateException.class);
    }

    @Test void successorUsesDatabaseOrderAndExcludesDepartingUser() {
        tx(() -> {
            MeetingMember first = members.saveAndFlush(new MeetingMember(room, outsider, RoomRole.MEMBER));
            jdbc.update("update meeting_members set joined_at = '2026-01-01T00:00:00Z' where room_id = ?", room);
            UUID expected = jdbc.queryForObject("select id from meeting_members where room_id=? and role='MEMBER' order by joined_at,id limit 1", UUID.class, room);
            assertThat(members.findSuccessor(room, owner).orElseThrow().getId()).isEqualTo(expected);
            assertThat(members.findSuccessor(room, member).orElseThrow().getId()).isEqualTo(first.getId());
            assertThat(members.findSuccessor(otherRoom, owner)).isEmpty(); return null;
        });
    }

    record Held(CountDownLatch release, Future<?> future) implements AutoCloseable {
        public void close() throws Exception { release.countDown(); future.get(8, TimeUnit.SECONDS); }
    }
    Held hold(boolean rollback, Runnable change) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> future = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
            access.lockByCode("ABC123"); change.run(); em.flush(); acquired.countDown(); await(release);
            if (rollback) status.setRollbackOnly(); return null;
        }));
        assertThat(acquired.await(8, TimeUnit.SECONDS)).isTrue();
        return new Held(release, future);
    }
    static void await(CountDownLatch latch) {
        try { if (!latch.await(12, TimeUnit.SECONDS)) throw new AssertionError("Latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
    void awaitDatabaseWait() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock'", Integer.class) > 0) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("No PostgreSQL lock waiter observed");
    }

    @Test void idAndCodeSerializeWhileOtherRoomsProceedAndCommitReleases() throws Exception {
        Future<?> waiting;
        try (Held held = hold(false, () -> jdbc.update("update meeting_rooms set name='committed' where id=?", room))) {
            waiting = pool.submit(() -> tx(() -> {
                MeetingRoom stale = rooms.findById(room).orElseThrow();
                MeetingRoom locked = access.lockOwner(room, owner);
                assertThat(locked).isSameAs(stale); assertThat(locked.getName()).isEqualTo("committed"); return null;
            }));
            awaitDatabaseWait(); assertThat(waiting.isDone()).isFalse();
            pool.submit(() -> tx(() -> access.lockByCode("DEF456"))).get(2, TimeUnit.SECONDS);
        }
        waiting.get(5, TimeUnit.SECONDS);
    }

    @Test void rollbackReleasesAndDiscardsChanges() throws Exception {
        Future<?> waiting;
        try (Held held = hold(true, () -> jdbc.update("update meeting_rooms set name='rolled back' where id=?", room))) {
            waiting = pool.submit(() -> tx(() -> { assertThat(access.lockMember(room, member).getName()).isEqualTo("room"); return null; }));
            awaitDatabaseWait();
        }
        waiting.get(5, TimeUnit.SECONDS);
    }

    @Test void codeDeletedWhileWaitingIsInvalid() throws Exception {
        Future<?> waiting;
        try (Held held = hold(false, () -> jdbc.update("delete from meeting_rooms where id=?", room))) {
            waiting = pool.submit(() -> tx(() -> access.lockByCode("ABC123")));
            awaitDatabaseWait();
        }
        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e.getCause()).getErrorCode()).isEqualTo(ErrorCode.ROOM_CODE_INVALID));
    }

    @Test void roleIsReadAfterLockDespiteManagedStaleMember() throws Exception {
        CountDownLatch loaded = new CountDownLatch(1), start = new CountDownLatch(1);
        Future<?> waiting = pool.submit(() -> tx(() -> {
            assertThat(members.findByRoomIdAndUserId(room, owner).orElseThrow().getRole()).isEqualTo(RoomRole.OWNER);
            loaded.countDown(); await(start);
            access.lockOwner(room, owner); return null;
        }));
        assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
        try (Held held = hold(false, () -> jdbc.update("update meeting_members set role='MEMBER' where room_id=? and user_id=?", room, owner))) {
            start.countDown(); awaitDatabaseWait();
        }
        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e.getCause()).getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_OWNER));
    }

    @RestController static class Probe {
        private final Runnable action;
        Probe(Runnable action) { this.action = action; }
        @GetMapping("/test/room-lock") public void run() { action.run(); }
    }
    @Test void realTimeoutBecomesHttp409RollsBackAndDoesNotLeakSettings() throws Exception {
        String baseline = tx(() -> (String) em.createNativeQuery("show lock_timeout").getSingleResult());
        java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
        try (Held held = hold(false, () -> {})) {
            var mvc = MockMvcBuilders.standaloneSetup(new Probe(() -> tx(() -> {
                rooms.saveAndFlush(new MeetingRoom("ZZZ999", "rollback", creator));
                long started = System.nanoTime();
                try { return access.lockMember(room, member); }
                finally { elapsed.set(System.nanoTime() - started); }
            }))).setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/test/room-lock")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ROOM_BUSY"));
            double seconds = elapsed.get() / 1_000_000_000.0;
            System.out.println("PostgreSQL room lock timeout measured seconds=" + seconds);
            assertThat(seconds).isBetween(2.7, 4.5);
            assertThat(jdbc.queryForObject("select count(*) from meeting_rooms where room_code='ZZZ999'", Integer.class)).isZero();
        }
        tx(() -> {
            access.lockOwner(room, owner);
            assertThat(em.createNativeQuery("show lock_timeout").getSingleResult()).isEqualTo(baseline); return null;
        });
        // Single-connection pool test would mask concurrent behavior; explicitly inspect every pooled connection.
        java.util.List<java.sql.Connection> connections = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) connections.add(jdbc.getDataSource().getConnection());
            for (var connection : connections) try (var statement = connection.createStatement(); var result = statement.executeQuery("show lock_timeout")) {
                result.next(); assertThat(result.getString(1)).isEqualTo(baseline);
            }
        } finally { for (var connection : connections) connection.close(); }
    }

    @Test void unrelatedDatabaseErrorsAreNotBusy() {
        assertThatThrownBy(() -> tx(() -> {
            rooms.saveAndFlush(new MeetingRoom("ABC123", "duplicate", creator)); return access.lockOwner(room, owner);
        })).isNotInstanceOf(BusinessException.class);
    }

    static void fails(ErrorCode code, Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
