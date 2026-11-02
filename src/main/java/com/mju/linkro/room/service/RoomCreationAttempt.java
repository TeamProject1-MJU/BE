package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomResponses.Created;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RoomCreationAttempt {
    private final MeetingRoomRepository rooms;
    private final MeetingMemberRepository members;
    private final UserRepository users;

    /** The proxy rolls back and closes each failed attempt before the caller retries. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Created create(UUID userId, String code, String name, Instant appointmentAt) {
        users.findById(userId).filter(user -> user.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        MeetingRoom room = new MeetingRoom(code, name, userId);
        room.schedule(appointmentAt);
        rooms.saveAndFlush(room);
        members.saveAndFlush(new MeetingMember(room.getId(), userId, RoomRole.OWNER));
        return new Created(room.getId(), code, name, room.getMaxMembers(), room.getStatus(), userId, appointmentAt);
    }
}
