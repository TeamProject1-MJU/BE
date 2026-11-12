package com.mju.linkro.room.service;

import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.realtime.RoomEventPayloads.*;
import com.mju.linkro.realtime.RoomEventType;
import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomResponses.Left;
import com.mju.linkro.room.repository.*;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller must acquire ALL required RoomAccess locks before the first mutation. */
@Service
@RequiredArgsConstructor
public class RoomLeaveProcessor {
    private final MeetingMemberRepository members;
    private final MeetingRoomRepository rooms;
    private final ChatSystemMessages systemMessages;
    private final RoomEvents events;

    @Transactional(propagation = Propagation.MANDATORY)
    public Left leaveLocked(MeetingRoom room, UUID userId) {
        var member = members.findByRoomIdAndUserId(room.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        // Scalar query avoids making decisions using a previously managed stale role.
        var role = members.findCurrentRole(room.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        members.delete(member);
        // Hibernate executes UPDATE before DELETE: release the unique OWNER slot first.
        members.flush();
        // The leaver stops receiving this room's events first, so even MEMBER_LEFT below goes only to the others.
        events.dropSubscriptions(room.getId(), userId);
        if (members.countByRoomId(room.getId()) == 0) {
            rooms.delete(room);
            // The last member left: no SYSTEM message in the deleted room and nobody left to tell.
            return new Left(true, null);
        }
        UUID newOwnerId = null;
        if (role == RoomRole.OWNER) {
            var successor = members.findSuccessor(room.getId(), userId)
                    .orElseThrow(() -> new IllegalStateException("Remaining room has no successor"));
            successor.promoteToOwner();
            newOwnerId = successor.getUserId();
        }
        systemMessages.left(room.getId(), userId, newOwnerId);
        events.publish(room.getId(), RoomEventType.MEMBER_LEFT, new MemberLeft(userId, newOwnerId));
        if (newOwnerId != null) events.publish(room.getId(), RoomEventType.OWNER_CHANGED, new OwnerChanged(userId, newOwnerId));
        return new Left(false, newOwnerId);
    }
}
