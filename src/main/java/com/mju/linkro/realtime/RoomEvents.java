package com.mju.linkro.realtime;

import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
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
public class RoomEvents {
    static final String TOPIC = "/topic/rooms/";

    private final SimpMessagingTemplate broker;
    private final StompSessions sessions;
    private final SimpleBrokerMessageHandler brokerHandler;

    /** The broker handler is lazy: it is built from the channels, whose interceptors reach services that use this class. */
    RoomEvents(SimpMessagingTemplate broker, StompSessions sessions, @Lazy SimpleBrokerMessageHandler brokerHandler) {
        this.broker = broker;
        this.sessions = sessions;
        this.brokerHandler = brokerHandler;
    }

    /** {@code /topic/rooms/{roomId}} with a canonical UUID, or null. */
    static UUID roomIdOf(String destination) {
        if (destination == null || !destination.startsWith(TOPIC)) return null;
        String id = destination.substring(TOPIC.length());
        try {
            UUID roomId = UUID.fromString(id);
            return roomId.toString().equals(id) ? roomId : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public void publish(UUID roomId, RoomEventType type, Object payload) {
        RoomEvent event = new RoomEvent(type, roomId, payload, Instant.now());
        afterCommit(() -> send(event));
    }

    /**
     * After commit: this user stops receiving the events of this room on every open connection (leave, withdrawal).
     * Our record is dropped and the subscription is also removed from the broker's own registry, in this thread and
     * before the call returns. Otherwise the broker would keep it, and a client that later reuses the subscription id
     * for another room would still get this room's events on it.
     */
    public void dropSubscriptions(UUID roomId, UUID userId) {
        afterCommit(() -> {
            for (StompSessions.Dropped dropped : sessions.dropRoom(userId, roomId)) {
                try {
                    SimpMessageHeaderAccessor unsubscribe = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
                    unsubscribe.setSessionId(dropped.sessionId());
                    unsubscribe.setSubscriptionId(dropped.subscriptionId());
                    brokerHandler.getSubscriptionRegistry()
                            .unregisterSubscription(MessageBuilder.createMessage(new byte[0], unsubscribe.getMessageHeaders()));
                } catch (RuntimeException failure) {
                    log.warn("Could not remove subscription {} of session {} from the broker", dropped.subscriptionId(),
                            dropped.sessionId(), failure);
                }
            }
        });
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
