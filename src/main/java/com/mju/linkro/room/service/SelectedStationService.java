package com.mju.linkro.room.service;

import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.subway.api.StationLookup;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelectedStationService {
    private final RoomAccess access;
    private final StationLookup stations;
    private final ChatSystemMessages systemMessages;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void select(UUID roomId, UUID userId, SelectedStationRequest request) {
        var room = access.lockOwner(roomId, userId);
        Long stationId = request.validatedStationId();
        var station = stations.requireSupported(stationId);
        Long previous = room.getSelectedStationId();
        room.confirmStation(stationId, Instant.now());
        // Choosing the already selected station again changes nothing anyone would notice: no SYSTEM message.
        if (!stationId.equals(previous)) systemMessages.stationSelected(roomId, userId, station.name(), previous != null);
        // #43: capture immutable station/confirmedAt payload here; publish only after outer commit.
    }

    /** Returns whether #43 should record/publish a change; an already open room is a no-op. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean clear(UUID roomId, UUID userId) {
        var room = access.lockOwner(roomId, userId);
        if (room.getSelectedStationId() == null && room.getConfirmedAt() == null) return false;
        room.clearSelectedStation();
        systemMessages.stationCleared(roomId, userId);
        // #43: only this changed branch records SYSTEM and registers a null/null event after commit.
        return true;
    }
}
