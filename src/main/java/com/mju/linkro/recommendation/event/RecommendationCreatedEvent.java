package com.mju.linkro.recommendation.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}) after a recommendation run is saved, including a run
 * without results. This is the hook for the WebSocket message {@code RECOMMENDATION_CREATED} (API 8.1, chapter 11):
 * the WebSocket module listens to it, this module does not know about STOMP.
 */
public record RecommendationCreatedEvent(UUID roomId, UUID runId, UUID requestedBy, Instant createdAt) {}
