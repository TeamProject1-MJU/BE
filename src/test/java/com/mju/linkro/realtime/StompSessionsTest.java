package com.mju.linkro.realtime;

import com.mju.linkro.realtime.RoomEventPayloads.MemberLeft;
import com.mju.linkro.realtime.RoomEventPayloads.SelectedStationChanged;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StompSessionsTest {
    final StompSessions sessions = new StompSessions();
    final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), room = UUID.randomUUID(), other = UUID.randomUUID();

    WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        return socket;
    }

    @Test void subscriptionCountsOnlyAfterItIsRecordedAndNotAfterClose() {
        sessions.opened(socket("s1"));
        assertThat(sessions.authenticated("s1", alice)).isTrue();
        assertThat(sessions.isSubscribed("s1", "sub-0")).isFalse();
        sessions.subscribed("s1", "sub-0", room);
        assertThat(sessions.isSubscribed("s1", "sub-0")).isTrue();
        sessions.unsubscribed("s1", "sub-0");
        assertThat(sessions.isSubscribed("s1", "sub-0")).isFalse();
        sessions.subscribed("s1", "sub-1", room);
        sessions.closed("s1");
        assertThat(sessions.isSubscribed("s1", "sub-1")).isFalse();
        assertThat(sessions.userOf("s1")).isNull();
    }

    @Test void unknownSessionCannotAuthenticateOrSubscribe() {
        assertThat(sessions.authenticated("ghost", alice)).isFalse();
        sessions.subscribed("ghost", "sub-0", room);
        assertThat(sessions.isSubscribed("ghost", "sub-0")).isFalse();
    }

    @Test void dropRoomRemovesOnlyThatUsersSubscriptionsToThatRoom() {
        sessions.opened(socket("a1"));
        sessions.opened(socket("a2"));
        sessions.opened(socket("b1"));
        sessions.authenticated("a1", alice);
        sessions.authenticated("a2", alice);
        sessions.authenticated("b1", bob);
        for (String id : new String[] {"a1", "a2", "b1"}) {
            sessions.subscribed(id, "room", room);
            sessions.subscribed(id, "other", other);
        }
        sessions.dropRoom(alice, room);
        assertThat(sessions.isSubscribed("a1", "room")).isFalse();
        assertThat(sessions.isSubscribed("a2", "room")).isFalse();
        assertThat(sessions.isSubscribed("a1", "other")).isTrue();
        assertThat(sessions.isSubscribed("b1", "room")).isTrue();
    }

    @Test void closeShutsEveryConnectionOfThatUserOnly() throws Exception {
        WebSocketSession a1 = socket("a1"), a2 = socket("a2"), b1 = socket("b1");
        sessions.opened(a1);
        sessions.opened(a2);
        sessions.opened(b1);
        sessions.authenticated("a1", alice);
        sessions.authenticated("a2", alice);
        sessions.authenticated("b1", bob);
        sessions.close(alice);
        verify(a1).close(any(CloseStatus.class));
        verify(a2).close(any(CloseStatus.class));
        verify(b1, never()).close(any(CloseStatus.class));
    }

    @Test void aSocketThatFailsToCloseDoesNotStopTheOthers() throws Exception {
        WebSocketSession a1 = socket("a1"), a2 = socket("a2");
        org.mockito.Mockito.doThrow(new java.io.IOException("gone")).when(a1).close(any(CloseStatus.class));
        sessions.opened(a1);
        sessions.opened(a2);
        sessions.authenticated("a1", alice);
        sessions.authenticated("a2", alice);
        sessions.close(alice);
        verify(a2).close(any(CloseStatus.class));
    }

    @Test void payloadsSerializeAsTheSpecDescribesThem() {
        JsonMapper mapper = JsonMapper.builder().build();
        UUID id = UUID.fromString("0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03");
        assertThat(mapper.writeValueAsString(new MemberLeft(id, null))).isEqualTo("{\"userId\":\"" + id + "\"}");
        assertThat(mapper.writeValueAsString(new MemberLeft(id, id))).contains("\"newOwnerId\"");
        assertThat(mapper.writeValueAsString(new SelectedStationChanged(null, null))).isEqualTo("{\"station\":null,\"confirmedAt\":null}");
        assertThat(mapper.writeValueAsString(new RoomEvent(RoomEventType.MEMBER_LEFT, id, new MemberLeft(id, null),
                Instant.parse("2026-11-11T00:00:00Z"))))
                .isEqualTo("{\"type\":\"MEMBER_LEFT\",\"roomId\":\"" + id + "\",\"payload\":{\"userId\":\"" + id + "\"},\"at\":\"2026-11-11T00:00:00Z\"}");
    }
}
