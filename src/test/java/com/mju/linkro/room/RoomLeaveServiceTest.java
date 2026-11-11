package com.mju.linkro.room;

import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomLeaveServiceTest {
    @Test void ordinaryMemberLeavesWithoutChangingOwner() {
        var members=mock(MeetingMemberRepository.class);
        var rooms=mock(MeetingRoomRepository.class);
        UUID user=UUID.randomUUID();
        var room=new MeetingRoom("ABC123","room",user);
        var departing=new MeetingMember(room.getId(),user,RoomRole.MEMBER);
        when(members.findByRoomIdAndUserId(room.getId(),user)).thenReturn(Optional.of(departing));
        when(members.findCurrentRole(room.getId(),user)).thenReturn(Optional.of(RoomRole.MEMBER));
        when(members.countByRoomId(room.getId())).thenReturn(1L);
        var result=new RoomLeaveProcessor(members,rooms,mock(com.mju.linkro.chat.service.ChatSystemMessages.class),mock(com.mju.linkro.realtime.RoomEvents.class)).leaveLocked(room,user);
        assertThat(result.roomDeleted()).isFalse(); assertThat(result.newOwnerId()).isNull();
        verify(members).delete(departing); verify(members).flush();
        verify(members,never()).findSuccessor(any(),any()); verifyNoInteractions(rooms);
    }

    @Test void lastMemberDeletesRoomAfterMembershipFlush() {
        var members=mock(MeetingMemberRepository.class);
        var rooms=mock(MeetingRoomRepository.class);
        UUID user=UUID.randomUUID();
        var room=new MeetingRoom("ABC123","room",user);
        var departing=new MeetingMember(room.getId(),user,RoomRole.OWNER);
        when(members.findByRoomIdAndUserId(room.getId(),user)).thenReturn(Optional.of(departing));
        when(members.findCurrentRole(room.getId(),user)).thenReturn(Optional.of(RoomRole.OWNER));
        var result=new RoomLeaveProcessor(members,rooms,mock(com.mju.linkro.chat.service.ChatSystemMessages.class),mock(com.mju.linkro.realtime.RoomEvents.class)).leaveLocked(room,user);
        var order=inOrder(members,rooms);
        order.verify(members).delete(departing); order.verify(members).flush(); order.verify(rooms).delete(room);
        assertThat(result.roomDeleted()).isTrue(); assertThat(result.newOwnerId()).isNull();
        verify(members,never()).findSuccessor(any(),any());
    }

    @Test void publicLeaveRejectsNonmemberBeforeProcessor() {
        var access=mock(RoomAccess.class); var processor=mock(RoomLeaveProcessor.class);
        UUID room=UUID.randomUUID(),user=UUID.randomUUID();
        when(access.lockMember(room,user)).thenThrow(new com.mju.linkro.common.exception.BusinessException(
                com.mju.linkro.common.exception.ErrorCode.NOT_ROOM_MEMBER));
        assertThatThrownBy(()->new RoomLeaveService(access,processor).leave(room,user))
            .isInstanceOf(com.mju.linkro.common.exception.BusinessException.class)
            .extracting(e->((com.mju.linkro.common.exception.BusinessException)e).getErrorCode())
            .isEqualTo(com.mju.linkro.common.exception.ErrorCode.NOT_ROOM_MEMBER);
        verifyNoInteractions(processor);
    }

    @Test void locksBeforeProcessingAndFlushesDeleteBeforePromotion() {
        UUID id=UUID.randomUUID(),user=UUID.randomUUID(),next=UUID.randomUUID();
        var access=mock(RoomAccess.class);
        var members=mock(MeetingMemberRepository.class);
        var rooms=mock(MeetingRoomRepository.class);
        var room=mock(MeetingRoom.class);
        var departing=new MeetingMember(id,user,RoomRole.OWNER);
        var successor=spy(new MeetingMember(id,next,RoomRole.MEMBER));
        when(access.lockMember(id,user)).thenReturn(room);
        when(room.getId()).thenReturn(id);
        when(members.findByRoomIdAndUserId(id,user)).thenReturn(Optional.of(departing));
        when(members.findCurrentRole(id,user)).thenReturn(Optional.of(RoomRole.OWNER));
        when(members.countByRoomId(id)).thenReturn(1L);
        when(members.findSuccessor(id,user)).thenReturn(Optional.of(successor));
        var result=new RoomLeaveService(access,new RoomLeaveProcessor(members,rooms,mock(com.mju.linkro.chat.service.ChatSystemMessages.class),mock(com.mju.linkro.realtime.RoomEvents.class))).leave(id,user);
        var order=inOrder(access,members,successor);
        order.verify(access).lockMember(id,user);
        order.verify(members).findByRoomIdAndUserId(id,user);
        order.verify(members).findCurrentRole(id,user);
        order.verify(members).delete(departing);
        order.verify(members).flush();
        order.verify(members).countByRoomId(id);
        order.verify(members).findSuccessor(id,user);
        order.verify(successor).promoteToOwner();
        assertThat(result.roomDeleted()).isFalse();
        assertThat(result.newOwnerId()).isEqualTo(next);
        verifyNoInteractions(rooms);
    }
}
