package com.mju.linkro.room;

import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;

class RoomServiceTest {
    @Test void obtainsOwnerLockBeforeAnyMutation() {
        RoomAccess access = mock(RoomAccess.class);
        RoomService service = new RoomService(access, mock(MeetingRoomRepository.class), mock(RoomReadRepository.class));
        UUID roomId = UUID.randomUUID(), user = UUID.randomUUID();
        MeetingRoom room = spy(new MeetingRoom("ABC123", "before", user));
        when(access.lockOwner(roomId, user)).thenReturn(room);
        service.update(roomId, user, JsonMapper.builder().build().readValue(
                "{\"name\":\"after\",\"appointmentAt\":\"2026-11-02T12:00:00Z\"}", RoomWriteRequest.class));
        var order = inOrder(access, room);
        order.verify(access).lockOwner(roomId, user);
        order.verify(room).rename("after");
        order.verify(room).schedule(Instant.parse("2026-11-02T12:00:00Z"));
        verifyNoMoreInteractions(access, room);
    }
}
