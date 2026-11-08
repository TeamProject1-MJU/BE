package com.mju.linkro.user.service;

import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import com.mju.linkro.common.exception.*;
import jakarta.persistence.*;
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
        String isolation = session.doReturningWork(connection -> {
            try (var statement = connection.prepareStatement("select current_setting('transaction_isolation')");
                    var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        });
        if (!"read committed".equals(isolation))
            throw new IllegalStateException("User locks require READ COMMITTED isolation");
        User user = users.findLockedById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
        // A locking query can return an already managed entity: refresh AFTER waiting.
        em.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public User lockActive(UUID id) {
        User user = lock(id);
        if (user.getDeletedAt() != null) throw new BusinessException(ErrorCode.TOKEN_INVALID);
        return user;
    }
}
