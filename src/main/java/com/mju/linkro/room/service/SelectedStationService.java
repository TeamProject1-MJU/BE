package com.mju.linkro.room.service;

import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.realtime.RoomEventType;
import com.mju.linkro.realtime.RoomEventPayloads.SelectedStationChanged;
import java.util.List;
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
    private final RoomReadRepository reads;
    private final RoomEvents events;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void select(UUID roomId, UUID userId, SelectedStationRequest request) {
        var room = access.lockOwner(roomId, userId);
        Long stationId = request.validatedStationId();
        var station = stations.requireSupported(stationId);
        Long previous = room.getSelectedStationId();
        Instant confirmedAt = Instant.now();
        room.confirmStation(stationId, confirmedAt);
        // Choosing the already selected station again changes nothing anyone would notice: no SYSTEM message.
        if (!stationId.equals(previous)) systemMessages.stationSelected(roomId, userId, station.name(), previous != null);
        events.publish(roomId, RoomEventType.SELECTED_STATION_CHANGED,
                new SelectedStationChanged(reads.stations(List.of(stationId)).get(stationId), confirmedAt));
    }

    /** Clears the station and publishes after commit only when changed; an already open room is a no-op. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean clear(UUID roomId, UUID userId) {
        var room = access.lockOwner(roomId, userId);
        if (room.getSelectedStationId() == null && room.getConfirmedAt() == null) return false;
        room.clearSelectedStation();
        systemMessages.stationCleared(roomId, userId);
        events.publish(roomId, RoomEventType.SELECTED_STATION_CHANGED, new SelectedStationChanged(null, null));
        return true;
    }
}
