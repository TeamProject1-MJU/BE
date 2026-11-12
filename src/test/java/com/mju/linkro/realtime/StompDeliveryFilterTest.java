package com.mju.linkro.realtime;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StompDeliveryFilterTest {
    final StompSessions sessions = new StompSessions();
    final StompDeliveryFilter filter = new StompDeliveryFilter(sessions);
    final UUID user = UUID.randomUUID(), roomA = UUID.randomUUID(), roomB = UUID.randomUUID();

    Message<?> frame(String sessionId, String subscriptionId, UUID room) {
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(sessionId);
        headers.setSubscriptionId(subscriptionId);
        headers.setDestination(RoomEvents.TOPIC + room);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    void connected() {
        var socket = mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(socket.getId()).thenReturn("s1");
        sessions.opened(socket);
        sessions.authenticated("s1", user);
    }

    @Test void aRecordedSubscriptionOfThatRoomGetsTheFrame() {
        connected();
        sessions.subscribed("s1", "sub-0", roomA);
        Message<?> frame = frame("s1", "sub-0", roomA);
        assertThat(filter.preSend(frame, null)).isSameAs(frame);
    }

    /** The broker still holds sub-0 for room A, but sub-0 is now recorded for room B: A's events are dropped. */
    @Test void aReusedSubscriptionIdDoesNotDeliverTheOldRoom() {
        connected();
        sessions.subscribed("s1", "sub-0", roomB);
        assertThat(filter.preSend(frame("s1", "sub-0", roomA), null)).isNull();
        assertThat(filter.preSend(frame("s1", "sub-0", roomB), null)).isNotNull();
    }

    @Test void aDroppedOrUnknownSubscriptionGetsNothing() {
        connected();
        assertThat(filter.preSend(frame("s1", "sub-0", roomA), null)).isNull();
        assertThat(filter.preSend(frame("ghost", "sub-0", roomA), null)).isNull();
    }

    @Test void framesWithoutASubscriptionIdPassLikeConnectedAndHeartbeats() {
        Message<?> connected = MessageBuilder.createMessage(new byte[0],
                SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK).getMessageHeaders());
        assertThat(filter.preSend(connected, null)).isSameAs(connected);
    }
}
