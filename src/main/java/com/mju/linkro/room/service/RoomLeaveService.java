package com.mju.linkro.room.service;

import com.mju.linkro.room.dto.RoomResponses.Left;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RoomLeaveService {
    private final RoomAccess access;
    private final RoomLeaveProcessor processor;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Left leave(UUID roomId, UUID userId) {
        var room = access.lockMember(roomId, userId);
        return processor.leaveLocked(room, userId);
    }
}
