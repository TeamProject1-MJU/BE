package com.mju.linkro.common.exception;

import java.util.Map;
import com.mju.linkro.common.response.ApiResponse;
import java.util.Objects;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, ?> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Map.of(), null);
    }

    /** Details must contain only information safe for clients to see. */
    public BusinessException(ErrorCode errorCode, Map<String, ?> details) {
        this(errorCode, details, null);
    }

    public BusinessException(ErrorCode errorCode, Throwable cause) {
        this(errorCode, Map.of(), cause);
    }

    /** Null detail keys/values are omitted; causes are reserved for server logging. */
    public BusinessException(ErrorCode errorCode, Map<String, ?> details, Throwable cause) {
        super(Objects.requireNonNull(errorCode).getMessage(), cause);
        this.errorCode = errorCode;
        this.details = ApiResponse.safeDetails(details);
    }
}
