package com.mju.linkro.ai.api;

import java.util.Objects;

/**
 * The AI result (spec 9.1), plus what it read from the sign. Direction-only requests apply an additional
 * explicit-evidence check in SignCheckService before returning the verdict.
 * Read fields ({@code recognized}) are null when unreadable; reason is null only for CORRECT.
 */
public record SignReading(SignResult result, SignReason reason, String stationName, String lineName, String headsign) {
    public SignReading {
        Objects.requireNonNull(result, "result");
        if (result == SignResult.CORRECT ? reason != null : reason == null || reason.result() != result) {
            throw new IllegalArgumentException("reason " + reason + " does not match result " + result);
        }
    }
}
