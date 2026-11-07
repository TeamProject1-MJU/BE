package com.mju.linkro.room;

import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoomLeaveServiceTest {
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
        var result=new RoomLeaveService(access,new RoomLeaveProcessor(members,rooms)).leave(id,user);
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
