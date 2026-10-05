package com.mju.linkro.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/** Common API envelope. Separate variants prevent data/error from leaking into each other. */
public sealed interface ApiResponse<T> {

    static <T> ApiResponse<T> success(T data) {
        return new Success<>(true, data);
    }

    static ApiResponse<Void> success() {
        return success(null);
    }

    static ApiResponse<Void> failure(String code, String message, Map<String, ?> details) {
        return new Failure(false, new Error(code, message, details));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Success<T>(boolean success, T data) implements ApiResponse<T> {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Failure(boolean success, Error error) implements ApiResponse<Void> {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Error(String code, String message, Map<String, ?> details) {
        public Error {
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }
}
