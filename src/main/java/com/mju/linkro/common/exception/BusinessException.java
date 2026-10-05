package com.mju.linkro.common.exception;

import java.util.Map;
import java.util.Objects;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, ?> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    /** Details must contain only information safe for clients to see. */
    public BusinessException(ErrorCode errorCode, Map<String, ?> details) {
        super(Objects.requireNonNull(errorCode).getMessage());
        this.errorCode = errorCode;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
}
