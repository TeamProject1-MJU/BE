package com.mju.linkro.room.service;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.dto.RoomWriteRequest;
import com.mju.linkro.room.dto.RoomResponses.Created;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class RoomCreationService {
    static final int MAX_ATTEMPTS = 5;
    private final RoomCreationAttempt attempt;
    private final RoomCodeGenerator codes;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Created create(UUID userId, RoomWriteRequest request) {
        String name = request.validatedName(true);
        var appointmentAt = request.validatedAppointmentAt();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            try { return attempt.create(userId, codes.generate(), name, appointmentAt); }
            catch (RuntimeException exception) {
                if (!codeCollision(exception)) throw exception;
                if (i == MAX_ATTEMPTS - 1) throw new BusinessException(ErrorCode.INTERNAL_ERROR, exception);
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private static boolean codeCollision(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && "uq_room_code".equals(violation.getConstraintName())) return true;
        }
        return false;
    }
}
