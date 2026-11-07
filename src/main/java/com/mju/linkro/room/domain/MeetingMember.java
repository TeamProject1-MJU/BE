package com.mju.linkro.room.domain;

import com.mju.linkro.common.entity.BaseUuidEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "meeting_members")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class MeetingMember extends BaseUuidEntity {
    @Column(name = "room_id", nullable = false, updatable = false)
    private UUID roomId;
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private RoomRole role = RoomRole.MEMBER;
    @Column(name = "departure_station_id")
    private Long departureStationId;
    @CreatedDate
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    public void changeDepartureStation(Long stationId) {
        this.departureStationId = Objects.requireNonNull(stationId);
    }

    public void promoteToOwner() {
        this.role = RoomRole.OWNER;
    }

    protected MeetingMember() {}

    public MeetingMember(UUID roomId, UUID userId, RoomRole role) {
        this.roomId = Objects.requireNonNull(roomId);
        this.userId = Objects.requireNonNull(userId);
        this.role = Objects.requireNonNull(role);
    }
}
