package com.mju.linkro.room.repository;

import com.mju.linkro.room.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface MeetingMemberRepository extends JpaRepository<MeetingMember, UUID> {
    Optional<MeetingMember> findByRoomIdAndUserId(UUID roomId, UUID userId);
    long countByRoomId(UUID roomId);

    // Scalar projection always reads the database, even if a stale member is managed.
    @Query("select m.role from MeetingMember m where m.roomId = :roomId and m.userId = :userId")
    Optional<RoomRole> findCurrentRole(@Param("roomId") UUID roomId, @Param("userId") UUID userId);

    // Remaining MEMBERs only; excludedUserId is the departing user's ID (not member ID).
    @Query(value = "select * from meeting_members where room_id = :roomId and user_id <> :excludedUserId "
            + "and role = 'MEMBER' order by joined_at asc, id asc limit 1", nativeQuery = true)
    Optional<MeetingMember> findSuccessor(@Param("roomId") UUID roomId,
            @Param("excludedUserId") UUID excludedUserId);
}
