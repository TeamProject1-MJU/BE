package com.mju.linkro.room.service;

import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.common.exception.*;
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
        if (members.countByRoomId(room.getId()) == 0) {
            rooms.delete(room);
            // #43: MEMBER_LEFT only after commit; never persist SYSTEM into this deleted room.
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
        // #43: MEMBER_LEFT / OWNER_CHANGED after commit.
        // Register immutable payloads with the OUTERMOST transaction; rollback must emit nothing.
        return new Left(false, newOwnerId);
    }
}
