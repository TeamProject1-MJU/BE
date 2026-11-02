package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomMembershipRequests.*;
import com.mju.linkro.room.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RoomMembershipService {
    private final RoomAccess access;
    private final MeetingMemberRepository members;
    private final RoomReadRepository reads;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Joined join(UUID userId, String code) {
        MeetingRoom room = access.lockByCode(code);
        if (members.findCurrentRole(room.getId(), userId).isPresent()) throw new BusinessException(ErrorCode.ALREADY_JOINED);
        if (members.countByRoomId(room.getId()) >= room.getMaxMembers()) throw new BusinessException(ErrorCode.ROOM_FULL);
        members.saveAndFlush(new MeetingMember(room.getId(), userId, RoomRole.MEMBER));
        // #43: register MEMBER_JOINED for delivery after successful commit here.
        return new Joined(room.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void departure(UUID roomId, UUID userId, Departure request) {
        access.lockMember(roomId, userId);
        Long stationId = request.validatedStationId();
        var station = reads.stations(List.of(stationId)).get(stationId);
        if (station == null) throw new BusinessException(ErrorCode.STATION_NOT_FOUND);
        if (!station.isSupported()) throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
        members.findByRoomIdAndUserId(roomId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_ROOM_MEMBER))
                .changeDepartureStation(stationId);
        // #43: register DEPARTURE_UPDATED for delivery after successful commit here.
    }
}
