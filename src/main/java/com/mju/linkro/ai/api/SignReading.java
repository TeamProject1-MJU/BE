package com.mju.linkro.ai.api;

import java.util.List;
import java.util.Objects;

/**
 * The AI result (spec 9.1), plus what it read from the sign. Direction-only requests apply an additional
 * evidence check in SignCheckService before returning the verdict.
 * Read fields ({@code recognized}) are null when unreadable; reason is null only for CORRECT.
 * {@code stationCode} (e.g. "202") and {@code towardStations} (station names on the side the sign's arrow marks for
 * this platform, nearest first; the next stop or major stations along the way) are internal clues for that check and
 * are not part of the 9.1 response.
 */
public record SignReading(SignResult result, SignReason reason, String stationName, String lineName, String headsign,
                          String stationCode, List<String> towardStations) {
    public SignReading {
        Objects.requireNonNull(result, "result");
        if (result == SignResult.CORRECT ? reason != null : reason == null || reason.result() != result) {
            throw new IllegalArgumentException("reason " + reason + " does not match result " + result);
        }
        towardStations = towardStations == null ? List.of()
                : towardStations.stream().filter(name -> name != null && !name.isBlank()).map(String::strip).toList();
    }

    public SignReading(SignResult result, SignReason reason, String stationName, String lineName, String headsign) {
        this(result, reason, stationName, lineName, headsign, null, List.of());
    }
}
