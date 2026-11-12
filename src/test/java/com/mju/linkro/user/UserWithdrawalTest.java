package com.mju.linkro.user;

import com.mju.linkro.user.service.*;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.room.service.*;
import com.mju.linkro.room.repository.MeetingMemberRepository;
import com.mju.linkro.room.domain.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserWithdrawalTest {
    UserAccess users=mock(UserAccess.class);
    RoomAccess access=mock(RoomAccess.class);
    MeetingMemberRepository members=mock(MeetingMemberRepository.class);
    RoomLeaveProcessor processor=mock(RoomLeaveProcessor.class);
    RefreshTokenRepository tokens=mock(RefreshTokenRepository.class);
    UserService service=new UserService(mock(UserRepository.class),tokens,users,members,access,processor,mock(com.mju.linkro.realtime.RoomEvents.class));

    @Test void acquiresEveryRoomBeforeFirstMutationAndSkipsMissingRoomOrMembership() {
        User user=new User("kakao","name"); UUID id=user.getId();
        UUID a=UUID.randomUUID(), gone=UUID.randomUUID(), b=UUID.randomUUID();
        MeetingRoom roomA=new MeetingRoom("ABC123","a",id), roomB=new MeetingRoom("ABC124","b",id);
        when(users.lockActive(id)).thenReturn(user);
        when(members.findRoomIdsForWithdrawal(id)).thenReturn(List.of(a,gone,b));
        when(access.lockForWithdrawal(a)).thenReturn(Optional.of(roomA));
        when(access.lockForWithdrawal(gone)).thenReturn(Optional.empty());
        when(access.lockForWithdrawal(b)).thenReturn(Optional.of(roomB));
        when(members.findCurrentRole(roomA.getId(),id)).thenReturn(Optional.of(RoomRole.OWNER));
        when(members.findCurrentRole(roomB.getId(),id)).thenReturn(Optional.empty());
        doAnswer(call->{ assertThat(user.getDeletedAt()).isNull(); return null; }).when(processor).leaveLocked(roomA,id);
        service.withdraw(id);
        var order=inOrder(users,members,access,processor,tokens);
        order.verify(users).lockActive(id); order.verify(members).findRoomIdsForWithdrawal(id);
        order.verify(access).lockForWithdrawal(a); order.verify(access).lockForWithdrawal(gone);
        order.verify(access).lockForWithdrawal(b); order.verify(members).findCurrentRole(roomA.getId(),id);
        order.verify(processor).leaveLocked(roomA,id);
        order.verify(members).findCurrentRole(roomB.getId(),id);
        order.verify(tokens).revokeAllByUserId(eq(id),any());
        verifyNoMoreInteractions(processor); assertThat(user.getDeletedAt()).isNotNull();
    }

    @Test void lockFailureDoesNotStartCleanupOrDeleteAccount() {
        User user=new User("kakao","name"); UUID id=user.getId(), room=UUID.randomUUID();
        when(users.lockActive(id)).thenReturn(user);
        when(members.findRoomIdsForWithdrawal(id)).thenReturn(List.of(room));
        when(access.lockForWithdrawal(room)).thenThrow(new IllegalStateException("lock failed"));
        assertThatThrownBy(()->service.withdraw(id)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(processor,tokens); assertThat(user.getDeletedAt()).isNull();
    }
}
