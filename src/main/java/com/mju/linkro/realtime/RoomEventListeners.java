package com.mju.linkro.realtime;

import com.mju.linkro.chat.event.MessageCreatedEvent;
import com.mju.linkro.realtime.RoomEventPayloads.RecommendationCreated;
import com.mju.linkro.recommendation.event.RecommendationCreatedEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Chat and recommendation announce themselves with Spring events that are already published after commit
 * (MessageCreatedEvent, RecommendationCreatedEvent); these listeners turn them into WebSocket events.
 */
@Component
@RequiredArgsConstructor
class RoomEventListeners {
    private final RoomEvents events;

    @EventListener
    void on(MessageCreatedEvent event) {
        events.send(new RoomEvent(RoomEventType.MESSAGE_CREATED, event.roomId(), event.message(), Instant.now()));
    }

    @EventListener
    void on(RecommendationCreatedEvent event) {
        events.send(new RoomEvent(RoomEventType.RECOMMENDATION_CREATED, event.roomId(),
                new RecommendationCreated(event.runId()), Instant.now()));
    }
}
