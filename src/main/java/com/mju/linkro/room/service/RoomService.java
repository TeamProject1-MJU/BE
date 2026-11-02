package com.mju.linkro.room.service;

import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.RoomResponses.*;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
public class RoomService {
    private final RoomAccess access;
    private final MeetingRoomRepository rooms;
    private final RoomReadRepository reads;

    public List<Summary> list(UUID userId) {
        List<MeetingRoom> participating = rooms.findParticipating(userId);
        var members = reads.members(participating.stream().map(MeetingRoom::getId).toList())
                .stream().collect(Collectors.groupingBy(MemberRow::roomId));
        var stations = reads.stations(participating.stream().map(MeetingRoom::getSelectedStationId)
                .filter(Objects::nonNull).distinct().toList());
        return participating.stream().map(room -> {
            var group = members.get(room.getId());
            return new Summary(room.getId(), room.getName(), room.getStatus(), role(group, userId),
                    group.size(), room.getMaxMembers(), room.getAppointmentAt(), selected(room, stations));
        }).toList();
    }

    public Detail detail(UUID roomId, UUID userId) {
        MeetingRoom room = access.requireMember(roomId, userId);
        List<MemberRow> rows = reads.members(List.of(roomId));
        Set<Long> stationIds = rows.stream().map(MemberRow::stationId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (room.getSelectedStationId() != null) stationIds.add(room.getSelectedStationId());
        var stations = reads.stations(stationIds);
        UUID owner = rows.stream().filter(row -> row.role() == RoomRole.OWNER).map(MemberRow::userId)
                .findFirst().orElseThrow(() -> new IllegalStateException("Room has no OWNER"));
        return new Detail(room.getId(), room.getName(), room.getStatus(), room.getMaxMembers(), room.getAppointmentAt(),
                owner, role(rows, userId), selected(room, stations), room.getConfirmedAt(), mapMembers(rows, stations));
    }

    public List<Member> members(UUID roomId, UUID userId) {
        access.requireMember(roomId, userId);
        var rows = reads.members(List.of(roomId));
        return mapMembers(rows, reads.stations(rows.stream().map(MemberRow::stationId).filter(Objects::nonNull).distinct().toList()));
    }

    public InviteCode inviteCode(UUID roomId, UUID userId) {
        return new InviteCode(access.requireOwner(roomId, userId).getRoomCode());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void update(UUID roomId, UUID userId, RoomWriteRequest request) {
        MeetingRoom room = access.lockOwner(roomId, userId);
        String name = request.validatedName(false);
        var appointmentAt = request.validatedAppointmentAt();
        if (request.name() != null) room.rename(name);
        if (request.appointmentAt() != null) room.schedule(appointmentAt);
        // #43: ROOM_UPDATED publication belongs here, inside the successful mutation boundary.
    }

    private static RoomRole role(List<MemberRow> rows, UUID userId) {
        return rows.stream().filter(row -> row.userId().equals(userId)).findFirst().orElseThrow().role();
    }
    private static Station selected(MeetingRoom room, Map<Long, Station> stations) {
        return room.getSelectedStationId() == null ? null : stations.get(room.getSelectedStationId());
    }
    private static List<Member> mapMembers(List<MemberRow> rows, Map<Long, Station> stations) {
        return rows.stream().map(row -> new Member(row.userId(), row.nickname(), row.role(), row.joinedAt(),
                row.stationId() == null ? null : stations.get(row.stationId()))).toList();
    }
}
