package com.mju.linkro.common.exception;

import com.mju.linkro.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException exception) {
        return failure(exception.getErrorCode(), exception.getDetails());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String field = null;
            for (var node : violation.getPropertyPath()) {
                if (node.getName() != null) {
                    field = node.getName();
                }
            }
            if (field != null) {
                details.putIfAbsent(field, violation.getMessage());
            }
        });
        return failure(ErrorCode.VALIDATION_ERROR, details);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.isBindingFailure()
                        ? "요청 값이 올바르지 않습니다."
                        : error.getDefaultMessage() == null ? "요청 값이 올바르지 않습니다."
                        : error.getDefaultMessage()));
        return new ResponseEntity<>(body(ErrorCode.VALIDATION_ERROR, details), headers, status);
    }

    /** Covers Spring MVC errors, preserving their HTTP status and protocol headers. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.value() == 500) {
            log.error("Unexpected Spring MVC exception", exception);
        }
        ApiResponse<Void> response = switch (status.value()) {
            case 400 -> body(ErrorCode.VALIDATION_ERROR, Map.of());
            case 405 -> body(ErrorCode.METHOD_NOT_ALLOWED, Map.of());
            case 415 -> body(ErrorCode.UNSUPPORTED_MEDIA_TYPE, Map.of());
            case 500 -> body(ErrorCode.INTERNAL_ERROR, Map.of());
            default -> ApiResponse.failure("HTTP_" + status.value(),
                    "요청을 처리할 수 없습니다.", Map.of());
        };
        return super.handleExceptionInternal(exception, response, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception) {
        log.error("Unexpected server exception", exception);
        return failure(ErrorCode.INTERNAL_ERROR, Map.of());
    }

    private ResponseEntity<Object> failure(ErrorCode code, Map<String, ?> details) {
        return ResponseEntity.status(code.getStatus()).body(body(code, details));
    }

    private ApiResponse<Void> body(ErrorCode code, Map<String, ?> details) {
        return ApiResponse.failure(code.getCode(), code.getMessage(), details);
    }
}
