package com.mju.linkro.realtime;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one way to send a room event (#43). Call it from the transaction that changes the room: the event goes out
 * only after that transaction commits, and nothing goes out when it rolls back. Without a transaction it goes out
 * at once. A delivery failure is logged and never reaches the caller, so a committed change is never undone.
 *
 * <pre>events.publish(roomId, RoomEventType.ROOM_UPDATED, payload);</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomEvents {
    static final String TOPIC = "/topic/rooms/";

    private final SimpMessagingTemplate broker;
    private final StompSessions sessions;

    public void publish(UUID roomId, RoomEventType type, Object payload) {
        RoomEvent event = new RoomEvent(type, roomId, payload, Instant.now());
        afterCommit(() -> send(event));
    }

    /** After commit: this user stops receiving the events of this room on every open connection (leave, withdrawal). */
    public void dropSubscriptions(UUID roomId, UUID userId) {
        afterCommit(() -> sessions.dropRoom(userId, roomId));
    }

    /** After commit: closes every connection of this user (withdrawal). */
    public void disconnect(UUID userId) {
        afterCommit(() -> sessions.close(userId));
    }

    /** Sends right now; for callers that already run after a commit (the chat and recommendation listeners). */
    void send(RoomEvent event) {
        try {
            broker.convertAndSend(TOPIC + event.roomId(), event);
        } catch (RuntimeException failure) {
            log.warn("Room event {} for room {} was not delivered", event.type(), event.roomId(), failure);
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                run(action);
            }
        });
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("After-commit realtime step failed", failure);
        }
    }
}
