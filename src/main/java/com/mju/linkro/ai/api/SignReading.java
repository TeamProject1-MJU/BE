package com.mju.linkro.ai.api;

import java.util.Objects;

/**
 * The AI result, used as-is (spec 9.1; not re-checked against our DB), plus what it read from the sign
 * ({@code recognized}). Read fields are null when unreadable; reason is null only for CORRECT. Field names follow spec 9.1.
 */
public record SignReading(SignResult result, SignReason reason, String stationName, String lineName, String headsign) {
    public SignReading {
        Objects.requireNonNull(result, "result");
        if (result == SignResult.CORRECT ? reason != null : reason == null || reason.result() != result) {
            throw new IllegalArgumentException("reason " + reason + " does not match result " + result);
        }
    }
}
