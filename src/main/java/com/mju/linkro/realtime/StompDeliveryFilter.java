package com.mju.linkro.realtime;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Outbound channel: a MESSAGE frame goes only when its session, subscription id and room (from the destination) all
 * match what {@link StompSessions} records. A subscription the broker still holds but we dropped (a left room, or a
 * subscription id the client later reused for another room) therefore delivers nothing.
 */
@Component
@RequiredArgsConstructor
class StompDeliveryFilter implements ChannelInterceptor {
    private final StompSessions sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Only a MESSAGE from the broker carries a subscription id; CONNECTED, ERROR and heartbeats pass.
        String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
        if (subscriptionId == null) return message;
        String sessionId = SimpMessageHeaderAccessor.getSessionId(message.getHeaders());
        UUID roomId = RoomEvents.roomIdOf(SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
        return sessions.isSubscribed(sessionId, subscriptionId, roomId) ? message : null;
    }
}
