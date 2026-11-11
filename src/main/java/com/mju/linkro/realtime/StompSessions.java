package com.mju.linkro.realtime;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * Who is connected and what they are subscribed to: STOMP session id -> user and (subscription id -> room).
 * A subscription is recorded only after its membership check, and an event is delivered only to a recorded
 * subscription ({@link StompDeliveryFilter}), so dropping a record stops that user's events at once.
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

    /** Returns false when the session is already gone. */
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

    void subscribed(String sessionId, String subscriptionId, UUID roomId) {
        Entry entry = entries.get(sessionId);
        if (entry != null) entry.rooms.put(subscriptionId, roomId);
    }

    void unsubscribed(String sessionId, String subscriptionId) {
        Entry entry = entries.get(sessionId);
        if (entry != null && subscriptionId != null) entry.rooms.remove(subscriptionId);
    }

    boolean isSubscribed(String sessionId, String subscriptionId) {
        Entry entry = entries.get(sessionId);
        return entry != null && subscriptionId != null && entry.rooms.containsKey(subscriptionId);
    }

    /** Drops this user's subscriptions to one room on all of their connections. */
    void dropRoom(UUID userId, UUID roomId) {
        for (Entry entry : entries.values()) {
            if (userId.equals(entry.userId)) entry.rooms.values().removeIf(roomId::equals);
        }
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
