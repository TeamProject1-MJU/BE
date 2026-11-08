package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.repository.*;
import jakarta.persistence.*;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
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

    /** Empty only when a concurrent last departure has deleted this room. */
    @Transactional(propagation = Propagation.MANDATORY)
    public java.util.Optional<MeetingRoom> lockForWithdrawal(UUID roomId) {
        return java.util.Optional.ofNullable(lock(() -> rooms.findLockedById(roomId).orElse(null)));
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
        Session session = em.unwrap(Session.class);
        // Query/refresh can otherwise flush or overwrite pre-lock edits. Never discard them silently.
        // This deliberately rejects pending changes anywhere in this persistence context.
        if (session.isDirty()) {
            throw new IllegalStateException("Acquire room locks before making pending entity changes");
        }
        // Read the actual database setting, even when Spring isolation metadata is null.
        String isolation = setting(session, "transaction_isolation");
        if (!"read committed".equals(isolation)) {
            throw new IllegalStateException("Room locks require READ COMMITTED isolation");
        }
        String previous = setting(session, "lock_timeout");
        lockTimeout(session, "3s");
        try {
            MeetingRoom room = query.get();
            // Refresh clean preloaded state after waiting; pre-lock edits were rejected above.
            // Hibernate 7.4/PostgreSQL retains the query's NO KEY UPDATE lock without upgrading it.
            if (room != null) em.refresh(room, LockModeType.PESSIMISTIC_WRITE);
            lockTimeout(session, previous);
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

    private static String setting(Session session, String name) {
        return session.doReturningWork(connection -> {
            try (var statement = connection.prepareStatement("select current_setting(?)")) {
                statement.setString(1, name);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private static void lockTimeout(Session session, String value) {
        session.doWork(connection -> {
            try (var statement = connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
                statement.setString(1, value);
                statement.execute();
            }
        });
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
