package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.dto.RoomMembershipRequests.*;
import com.mju.linkro.room.dto.RoomResponses.Joined;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Nontransactional boundary around the separate RoomMembershipService proxy. The proxy completes
 * rollback before this service translates only SQLSTATE 23505 / uq_member_room_user to ALREADY_JOINED.
 * Normal joins are serialized by the room lock and detect duplicates before INSERT; this UNIQUE
 * conversion is defensive. Other failures propagate, and no work continues in a failed transaction.
 */
@Service
@RequiredArgsConstructor
public class RoomJoinService {
    private final RoomMembershipService membership;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Joined join(UUID userId, Join request) {
        String code = request.validatedCode();
        try { return membership.join(userId, code); }
        catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && "uq_member_room_user".equals(violation.getConstraintName()))
                    throw new BusinessException(ErrorCode.ALREADY_JOINED, exception);
            }
            throw exception;
        }
    }
}
