package com.mju.linkro.user.service;

import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.common.exception.*;
import jakarta.persistence.*;
import java.sql.SQLException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database row lock shared by account, membership and token writers. */
@Service
@RequiredArgsConstructor
public class UserAccess {
    private final UserRepository users;
    @PersistenceContext private EntityManager em;

    @Transactional(propagation = Propagation.MANDATORY)
    public User lock(UUID id) {
        var session = em.unwrap(Session.class);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("User locks require a read-write transaction");
        if (session.isDirty())
            throw new IllegalStateException("Acquire user lock before entity changes");
        // Use JDBC on this session so reading the actual PostgreSQL setting cannot auto-flush.
        String isolation = setting(session, "transaction_isolation");
        if (!"read committed".equals(isolation))
            throw new IllegalStateException("User locks require READ COMMITTED isolation");
        String previous = setting(session, "lock_timeout");
        lockTimeout(session, "3s");
        try {
            User user = users.findLockedById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
            // A locking query can return an already managed entity: refresh AFTER waiting.
            em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
            lockTimeout(session, previous);
            return user;
        } catch (RuntimeException exception) {
            // PostgreSQL has aborted the transaction: do not restore in finally and mask 55P03.
            // SET LOCAL is discarded by the outer transaction's rollback.
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState()))
                    throw new BusinessException(ErrorCode.ROOM_BUSY, exception);
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

    @Transactional(propagation = Propagation.MANDATORY)
    public User lockActive(UUID id) {
        User user = lock(id);
        if (user.getDeletedAt() != null) throw new BusinessException(ErrorCode.TOKEN_INVALID);
        return user;
    }
}
