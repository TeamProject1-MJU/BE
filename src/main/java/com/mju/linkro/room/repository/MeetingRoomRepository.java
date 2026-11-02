package com.mju.linkro.room.repository;

import com.mju.linkro.room.domain.MeetingRoom;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.*;

public interface MeetingRoomRepository extends JpaRepository<MeetingRoom, UUID> {
    @Query("select r from MeetingRoom r, MeetingMember m where m.roomId = r.id and m.userId = :userId order by r.createdAt desc, r.id desc")
    java.util.List<MeetingRoom> findParticipating(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select r from MeetingRoom r where r.id = :id")
    Optional<MeetingRoom> findLockedById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("select r from MeetingRoom r where r.roomCode = :code")
    Optional<MeetingRoom> findLockedByRoomCode(@Param("code") String code);
}
