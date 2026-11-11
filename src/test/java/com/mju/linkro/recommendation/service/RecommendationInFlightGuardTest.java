package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.recommendation.service.RecommendationInFlightGuard.Ticket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationInFlightGuardTest {
    static final UUID ROOM = UUID.randomUUID();
    static final UUID OTHER = UUID.randomUUID();

    static class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    final ManualClock clock = new ManualClock();
    final RecommendationInFlightGuard guard = new RecommendationInFlightGuard(Duration.ofSeconds(60), clock);

    @Test
    void aRoomIsHeldUntilReleased() {
        Ticket ticket = guard.tryAcquire(ROOM).orElseThrow();
        assertThat(ticket.expiresAt()).isEqualTo(clock.now.plusSeconds(60));
        assertThat(guard.tryAcquire(ROOM)).isEmpty();
        guard.release(ticket);
        assertThat(guard.tryAcquire(ROOM)).isPresent();
    }

    @Test
    void roomsDoNotBlockEachOther() {
        Ticket room = guard.tryAcquire(ROOM).orElseThrow();
        Ticket other = guard.tryAcquire(OTHER).orElseThrow();
        assertThat(guard.tryAcquire(ROOM)).isEmpty();
        guard.release(other);
        assertThat(guard.tryAcquire(ROOM)).isEmpty();
        guard.release(room);
        assertThat(guard.tryAcquire(ROOM)).isPresent();
    }

    @Test
    void anExpiredHoldIsTakenOver() {
        guard.tryAcquire(ROOM).orElseThrow();
        clock.now = clock.now.plusSeconds(59);
        assertThat(guard.tryAcquire(ROOM)).isEmpty();
        clock.now = clock.now.plusSeconds(1);
        assertThat(guard.tryAcquire(ROOM)).isPresent();
    }

    @Test
    void aLateReleaseOfAnExpiredTicketKeepsTheNewHold() {
        Ticket old = guard.tryAcquire(ROOM).orElseThrow();
        clock.now = clock.now.plusSeconds(61);
        Ticket current = guard.tryAcquire(ROOM).orElseThrow();
        assertThat(current.token()).isNotEqualTo(old.token());
        guard.release(old);
        assertThat(guard.tryAcquire(ROOM)).isEmpty();
        guard.release(current);
        assertThat(guard.tryAcquire(ROOM)).isPresent();
    }

    @Test
    void releasingTwiceOrAfterATakeOverIsHarmless() {
        Ticket ticket = guard.tryAcquire(ROOM).orElseThrow();
        guard.release(ticket);
        guard.release(ticket);
        assertThat(guard.tryAcquire(ROOM)).isPresent();
    }

    @Test
    void theMaximumHoldComesFromTheSettingAndMustBePositive() {
        RecommendationProperties properties = new RecommendationProperties();
        assertThat(properties.getInProgressMaxSeconds()).isEqualTo(60);
        properties.setInProgressMaxSeconds(0);
        assertThatThrownBy(() -> new RecommendationInFlightGuard(properties)).isInstanceOf(IllegalArgumentException.class);
    }
}
