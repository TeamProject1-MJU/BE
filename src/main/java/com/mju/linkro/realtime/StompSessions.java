package com.mju.linkro.realtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Who is connected and what they are subscribed to: STOMP session id -> user and (subscription id -> room).
 * A subscription is recorded before its membership check (and removed again when the check fails), and an event is
 * delivered only when session, subscription id and room all match a record ({@link StompDeliveryFilter}), so
 * dropping a record stops that user's events at once.
 */
@Slf4j
@Component
class StompSessions {
    private static final class Entry {
        final WebSocketSession socket;
        volatile UUID userId;
        final Map<String, UUID> rooms = new ConcurrentHashMap<>();

        Entry(WebSocketSession socket) { this.socket = socket; }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    void opened(WebSocketSession socket) {
        entries.put(socket.getId(), new Entry(socket));
    }

    /** Connection gone (any reason): forget the session and its subscriptions. */
    void closed(String sessionId) {
        entries.remove(sessionId);
    }

    /**
     * Returns false when the session is already gone. From here on {@link #close} and {@link #dropRoom} find this
     * connection, so CONNECT registers it before it re-checks that the user is still active.
     */
    boolean authenticated(String sessionId, UUID userId) {
        Entry entry = entries.get(sessionId);
        if (entry == null) return false;
        entry.userId = userId;
        return true;
    }

    /** The user of an authenticated session, or null. */
    UUID userOf(String sessionId) {
        Entry entry = entries.get(sessionId);
        return entry == null ? null : entry.userId;
    }

    /**
     * Records a subscription. False when the session is gone or the subscription id is already in use on this
     * connection: STOMP ids are unique per connection, and reusing one would leave two broker subscriptions behind
     * one of our records.
     */
    boolean subscribed(String sessionId, String subscriptionId, UUID roomId) {
        Entry entry = entries.get(sessionId);
        return entry != null && entry.rooms.putIfAbsent(subscriptionId, roomId) == null;
    }

    void unsubscribed(String sessionId, String subscriptionId) {
        Entry entry = entries.get(sessionId);
        if (entry != null && subscriptionId != null) entry.rooms.remove(subscriptionId);
    }

    /** True only when this session's subscription id is recorded for exactly this room. */
    boolean isSubscribed(String sessionId, String subscriptionId, UUID roomId) {
        Entry entry = entries.get(sessionId);
        return entry != null && subscriptionId != null && roomId != null && roomId.equals(entry.rooms.get(subscriptionId));
    }

    /** One subscription of a user that was dropped. */
    record Dropped(String sessionId, String subscriptionId) {}

    /** Drops this user's subscriptions to one room on all of their connections and returns what was dropped. */
    List<Dropped> dropRoom(UUID userId, UUID roomId) {
        List<Dropped> dropped = new ArrayList<>();
        for (Map.Entry<String, Entry> session : entries.entrySet()) {
            Entry entry = session.getValue();
            if (!userId.equals(entry.userId)) continue;
            entry.rooms.entrySet().removeIf(subscription -> {
                boolean match = roomId.equals(subscription.getValue());
                if (match) dropped.add(new Dropped(session.getKey(), subscription.getKey()));
                return match;
            });
        }
        return dropped;
    }

    /** Closes every connection of this user. */
    void close(UUID userId) {
        for (Entry entry : entries.values()) {
            if (!userId.equals(entry.userId)) continue;
            try {
                entry.socket.close(CloseStatus.POLICY_VIOLATION.withReason("ACCOUNT_WITHDRAWN"));
            } catch (Exception failure) {
                log.warn("Could not close WebSocket session {}", entry.socket.getId(), failure);
            }
        }
    }
}
