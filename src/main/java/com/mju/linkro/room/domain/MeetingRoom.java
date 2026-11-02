package com.mju.linkro.room.domain;

import com.mju.linkro.common.entity.BaseUuidTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "meeting_rooms")
@Getter
public class MeetingRoom extends BaseUuidTimeEntity {
    @Column(name = "room_code", nullable = false, length = 6)
    private String roomCode;
    @Column(nullable = false, length = 30)
    private String name;
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;
    @Column(name = "max_members", nullable = false, updatable = false)
    private int maxMembers = 4;
    @Column(name = "appointment_at")
    private Instant appointmentAt;
    @Column(name = "selected_station_id")
    private Long selectedStationId;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected MeetingRoom() {}

    public MeetingRoom(String roomCode, String name, UUID createdBy) {
        this.roomCode = Objects.requireNonNull(roomCode);
        this.name = RoomName.normalize(name);
        this.createdBy = Objects.requireNonNull(createdBy);
    }

    public void schedule(Instant appointmentAt) { this.appointmentAt = appointmentAt; }

    public void rename(String name) { this.name = RoomName.normalize(name); }

    public void confirmStation(Long stationId, Instant confirmedAt) {
        Objects.requireNonNull(stationId);
        Objects.requireNonNull(confirmedAt);
        this.selectedStationId = stationId;
        this.confirmedAt = confirmedAt;
    }

    public void clearSelectedStation() {
        selectedStationId = null;
        confirmedAt = null;
    }

    @Transient
    public RoomStatus getStatus() {
        return selectedStationId == null ? RoomStatus.OPEN : RoomStatus.CONFIRMED;
    }
}
