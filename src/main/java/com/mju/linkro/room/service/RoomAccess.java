package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.repository.*;
import jakarta.persistence.*;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Inject this bean into a separate transactional service; locks live until its transaction ends. */
@Service
@RequiredArgsConstructor
public class RoomAccess {
    private final MeetingRoomRepository rooms;
    private final MeetingMemberRepository members;
    @PersistenceContext private EntityManager em;

    @Transactional(readOnly = true)
    public MeetingRoom requireMember(UUID roomId, UUID userId) {
        MeetingRoom room = rooms.findById(roomId).orElseThrow(() -> error(ErrorCode.ROOM_NOT_FOUND));
        checkRole(roomId, userId, false);
        return room;
    }

    @Transactional(readOnly = true)
    public MeetingRoom requireOwner(UUID roomId, UUID userId) {
        MeetingRoom room = rooms.findById(roomId).orElseThrow(() -> error(ErrorCode.ROOM_NOT_FOUND));
        checkRole(roomId, userId, true);
        return room;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MeetingRoom lockMember(UUID roomId, UUID userId) {
        MeetingRoom room = lock(() -> rooms.findLockedById(roomId)
                .orElseThrow(() -> error(ErrorCode.ROOM_NOT_FOUND)));
        checkRole(roomId, userId, false);
        return room;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MeetingRoom lockOwner(UUID roomId, UUID userId) {
        MeetingRoom room = lock(() -> rooms.findLockedById(roomId)
                .orElseThrow(() -> error(ErrorCode.ROOM_NOT_FOUND)));
        checkRole(roomId, userId, true);
        return room;
    }

    /** Joining users need not already be members. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MeetingRoom lockByCode(String code) {
        return lock(() -> rooms.findLockedByRoomCode(code)
                .orElseThrow(() -> error(ErrorCode.ROOM_CODE_INVALID)));
    }

    private void checkRole(UUID roomId, UUID userId, boolean owner) {
        RoomRole role = members.findCurrentRole(roomId, userId)
                .orElseThrow(() -> error(ErrorCode.NOT_ROOM_MEMBER));
        if (owner && role != RoomRole.OWNER) throw error(ErrorCode.NOT_ROOM_OWNER);
    }

    private MeetingRoom lock(Supplier<MeetingRoom> query) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Room locks require an existing read-write transaction");
        }
        // READ COMMITTED is necessary to see commits made while waiting for the room lock.
        String isolation = (String) em.createNativeQuery("show transaction_isolation").getSingleResult();
        if (!"read committed".equals(isolation)) {
            throw new IllegalStateException("Room locks require READ COMMITTED isolation");
        }
        String previous = (String) em.createNativeQuery("show lock_timeout").getSingleResult();
        em.createNativeQuery("select set_config('lock_timeout', '3s', true)").getSingleResult();
        try {
            MeetingRoom room = query.get();
            // Also refresh a room that the caller loaded before waiting. Do not flush stale state.
            em.refresh(room, LockModeType.PESSIMISTIC_WRITE);
            em.createNativeQuery("select set_config('lock_timeout', :value, true)")
                    .setParameter("value", previous).getSingleResult();
            return room;
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                    throw new BusinessException(ErrorCode.ROOM_BUSY, exception);
                }
            }
            throw exception;
        }
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
