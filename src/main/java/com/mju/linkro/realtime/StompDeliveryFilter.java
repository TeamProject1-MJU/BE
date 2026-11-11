package com.mju.linkro.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** Outbound channel: a MESSAGE frame goes only to a subscription that {@link StompSessions} still records. */
@Component
@RequiredArgsConstructor
class StompDeliveryFilter implements ChannelInterceptor {
    private final StompSessions sessions;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // Only a MESSAGE from the broker carries a subscription id; CONNECTED, ERROR and heartbeats pass.
        String subscriptionId = SimpMessageHeaderAccessor.getSubscriptionId(message.getHeaders());
        if (subscriptionId != null && !sessions.isSubscribed(SimpMessageHeaderAccessor.getSessionId(message.getHeaders()), subscriptionId)) {
            return null;
        }
        return message;
    }
}
