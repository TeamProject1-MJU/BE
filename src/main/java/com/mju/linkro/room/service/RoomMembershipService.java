package com.mju.linkro.room.service;

import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.realtime.RoomEventPayloads.*;
import com.mju.linkro.realtime.RoomEventType;
import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomMembershipRequests.*;
import com.mju.linkro.room.dto.RoomResponses.Joined;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.user.service.UserAccess;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Owns the READ COMMITTED write transactions and room locks for membership mutations.
 * Join is called through this separate Spring proxy so RoomJoinService can translate a defensive
 * member UNIQUE violation only after rollback. Normal joins serialize under lockByCode and check
 * duplicate membership and capacity before saving.
 */
@Service
@RequiredArgsConstructor
public class RoomMembershipService {
    private final RoomAccess access;
    private final UserAccess users;
    private final MeetingMemberRepository members;
    private final RoomReadRepository reads;
    private final ChatSystemMessages systemMessages;
    private final RoomEvents events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Joined join(UUID userId, String code) {
        var user = users.lockActive(userId);
        MeetingRoom room = access.lockByCode(code);
        if (members.findCurrentRole(room.getId(), userId).isPresent()) throw new BusinessException(ErrorCode.ALREADY_JOINED);
        if (members.countByRoomId(room.getId()) >= room.getMaxMembers()) throw new BusinessException(ErrorCode.ROOM_FULL);
        members.saveAndFlush(new MeetingMember(room.getId(), userId, RoomRole.MEMBER));
        systemMessages.joined(room.getId(), userId);
        events.publish(room.getId(), RoomEventType.MEMBER_JOINED, new MemberJoined(userId, user.getNickname()));
        return new Joined(room.getId());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void departure(UUID roomId, UUID userId, Departure request) {
        access.lockMember(roomId, userId);
        Long stationId = request.validatedStationId();
        var station = reads.stations(List.of(stationId)).get(stationId);
        if (station == null) throw new BusinessException(ErrorCode.STATION_NOT_FOUND);
        if (!station.isSupported()) throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
        var member = members.findByRoomIdAndUserId(roomId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        boolean changed = !stationId.equals(member.getDepartureStationId());
        member.changeDepartureStation(stationId);
        // Reapplying the same station succeeds without changing the stored value, and sends no event.
        if (changed) events.publish(roomId, RoomEventType.DEPARTURE_UPDATED, new DepartureUpdated(userId, station));
    }
}
