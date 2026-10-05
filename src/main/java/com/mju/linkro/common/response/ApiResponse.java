package com.mju.linkro.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

/** Common API envelope. Separate variants prevent data/error from leaking into each other. */
public sealed interface ApiResponse<T> {

    static <T> ApiResponse<T> success(T data) {
        return new Success<>(data);
    }

    static ApiResponse<Void> success() {
        return success(null);
    }

    static ApiResponse<Void> failure(String code, String message, Map<String, ?> details) {
        return new Failure( new Error(code, message, details));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Success<T>(T data) implements ApiResponse<T> {
        @JsonProperty("success")
        public boolean success() { return true; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Failure(Error error) implements ApiResponse<Void> {
        public Failure { Objects.requireNonNull(error); }
        @JsonProperty("success")
        public boolean success() { return false; }
    }

    /** Drops null keys/values and snapshots client-safe details as an immutable map. */
    static Map<String, ?> safeDetails(Map<String, ?> details) {
        if (details == null) { return Map.of(); }
        Map<String, Object> copy = new java.util.TreeMap<>();
        details.forEach((key, value) -> {
            if (key != null && value != null) { copy.put(key, value); }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Error(String code, String message, Map<String, ?> details) {
        public Error {
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
            details = safeDetails(details);
        }
    }
}
