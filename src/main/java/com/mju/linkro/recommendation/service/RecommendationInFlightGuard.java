package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.RecommendationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * One recommendation calculation per room at a time (#98). In memory, so it only protects a single server.
 *
 * <p>{@link #tryAcquire} hands out a ticket or nothing when another request of the room holds one. A ticket expires
 * after {@code linkro.recommendation.in-progress-max-seconds} (default 60) so a request that never finishes cannot
 * block its room for good; the next request then takes over. {@link #release} only removes the ticket it is given,
 * so a late release of an expired ticket never frees the newer one (each ticket has its own token).
 */
@Slf4j
@Component
public class RecommendationInFlightGuard {
    /** A room's hold. {@code token} is unique per ticket, so two tickets of one room never compare equal. */
    public record Ticket(UUID roomId, long token, Instant expiresAt) {}

    private final ConcurrentHashMap<UUID, Ticket> holders = new ConcurrentHashMap<>();
    private final AtomicLong tokens = new AtomicLong();
    private final Duration maxHold;
    private final Clock clock;

    @Autowired
    public RecommendationInFlightGuard(RecommendationProperties properties) {
        this(Duration.ofSeconds(properties.getInProgressMaxSeconds()), Clock.systemUTC());
    }

    RecommendationInFlightGuard(Duration maxHold, Clock clock) {
        if (maxHold == null || maxHold.isNegative() || maxHold.isZero()) {
            throw new IllegalArgumentException("linkro.recommendation.in-progress-max-seconds must be positive");
        }
        this.maxHold = maxHold;
        this.clock = clock;
    }

    /** The room's ticket, or empty when another unexpired one holds the room. */
    public Optional<Ticket> tryAcquire(UUID roomId) {
        Instant now = clock.instant();
        Ticket fresh = new Ticket(roomId, tokens.incrementAndGet(), now.plus(maxHold));
        Ticket held = holders.compute(roomId, (id, current) -> {
            if (current == null) return fresh;
            if (now.isBefore(current.expiresAt())) return current;
            log.warn("Recommendation guard of room {} expired after {} s without release; a new request takes over",
                    roomId, maxHold.toSeconds());
            return fresh;
        });
        return held == fresh ? Optional.of(fresh) : Optional.empty();
    }

    /** Frees the room if this ticket still holds it; an expired ticket that was taken over changes nothing. */
    public void release(Ticket ticket) {
        holders.remove(ticket.roomId(), ticket);
    }
}
