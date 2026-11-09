package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomMembershipRequests.*;
import com.mju.linkro.room.dto.RoomResponses.Station;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomMembershipServiceTest {
    private final RoomAccess access = mock(RoomAccess.class);
    private final MeetingMemberRepository members = mock(MeetingMemberRepository.class);
    private final RoomReadRepository reads = mock(RoomReadRepository.class);
    private final RoomMembershipService service = new RoomMembershipService(access, mock(com.mju.linkro.user.service.UserAccess.class), members, reads);
    private final UUID room = UUID.randomUUID(), user = UUID.randomUUID();

    @Test void locksBeforeDuplicateCountAndSave() {
        MeetingRoom entity = mock(MeetingRoom.class);
        when(access.lockByCode("ABC123")).thenReturn(entity);
        when(entity.getId()).thenReturn(room);
        when(entity.getMaxMembers()).thenReturn(4);
        service.join(user, "ABC123");
        var order = inOrder(access, members);
        order.verify(access).lockByCode("ABC123");
        order.verify(members).findCurrentRole(room, user);
        order.verify(members).countByRoomId(room);
        order.verify(members).saveAndFlush(argThat(m -> m.getRoomId().equals(room) && m.getUserId().equals(user)
                && m.getRole() == RoomRole.MEMBER && m.getDepartureStationId() == null));
    }

    @Test void locksBeforeValidationAndChangesOnlyOwnMember() {
        MeetingMember member = spy(new MeetingMember(room, user, RoomRole.OWNER));
        when(reads.stations(List.of(105L))).thenReturn(Map.of(105L, new Station(105L,"station",true,List.of())));
        when(members.findByRoomIdAndUserId(room,user)).thenReturn(Optional.of(member));
        service.departure(room,user,JsonMapper.builder().build().readValue("{\"departureStationId\":105}",Departure.class));
        var order = inOrder(access, reads, members, member);
        order.verify(access).lockMember(room,user);
        order.verify(reads).stations(List.of(105L));
        order.verify(members).findByRoomIdAndUserId(room,user);
        order.verify(member).changeDepartureStation(105L);
    }

    @Test void nullDepartureIsRejectedAfterLockBeforeAnyLookupOrMutation() {
        var request = JsonMapper.builder().build().readValue("{\"departureStationId\":null}",Departure.class);
        assertThatThrownBy(() -> service.departure(room,user,request)).isInstanceOf(BusinessException.class);
        verify(access).lockMember(room,user);
        verifyNoInteractions(reads,members);
    }
}
