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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException exception) {
        if (exception.getErrorCode().getStatus().is5xxServerError()) {
            log.error("Server business exception: {}", exception.getErrorCode().getCode(), exception);
        }
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
                addReason(details, field, violation.getMessage());
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
                addReason(details, error.getField(), error.isBindingFailure()
                        ? ErrorCode.VALIDATION_ERROR.getMessage() : error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                addReason(details, "_global", error.getDefaultMessage()));
        return new ResponseEntity<>(body(ErrorCode.VALIDATION_ERROR, details), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) {
            return handleExceptionInternal(exception, null, headers, status, request);
        }
        Map<String, String> details = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            var parameter = result.getMethodParameter();
            var query = parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestParam.class);
            var path = parameter.getParameterAnnotation(org.springframework.web.bind.annotation.PathVariable.class);
            String name = parameter.getParameterName();
            if (query != null && !query.name().isEmpty()) { name = query.name(); }
            else if (query != null && !query.value().isEmpty()) { name = query.value(); }
            else if (path != null && !path.name().isEmpty()) { name = path.name(); }
            else if (path != null && !path.value().isEmpty()) { name = path.value(); }
            String key = name == null ? "arg" + parameter.getParameterIndex() : name;
            result.getResolvableErrors().forEach(error -> addReason(details, key, error.getDefaultMessage()));
        });
        return new ResponseEntity<>(body(ErrorCode.VALIDATION_ERROR, details), headers, status);
    }

    /** Duplicate reasons use the lexicographically smallest message, independent of iteration order. */
    private static void addReason(Map<String, String> details, String key, String message) {
        String reason = message == null ? ErrorCode.VALIDATION_ERROR.getMessage() : message;
        details.merge(key, reason, (left, right) -> left.compareTo(right) <= 0 ? left : right);
    }

    /** Covers Spring MVC errors, preserving their HTTP status and protocol headers. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Unexpected Spring MVC exception", exception);
        }
        ApiResponse<Void> response = switch (status.value()) {
            case 400 -> body(ErrorCode.VALIDATION_ERROR, Map.of());
            case 404 -> body(ErrorCode.NOT_FOUND, Map.of());
            case 406 -> body(ErrorCode.NOT_ACCEPTABLE, Map.of());
            case 405 -> body(ErrorCode.METHOD_NOT_ALLOWED, Map.of());
            case 415 -> body(ErrorCode.UNSUPPORTED_MEDIA_TYPE, Map.of());
            case 500 -> body(ErrorCode.INTERNAL_ERROR, Map.of());
            default -> body(ErrorCode.INTERNAL_ERROR, Map.of());
        };
        // A 406 still needs a JSON envelope even when Accept excludes JSON.
        if (status.value() == 406) {
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.putAll(headers);
            responseHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers = responseHeaders;
        }
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
