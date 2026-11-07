package com.mju.linkro.subway.api;

import java.util.List;

/**
 * Congestion of a route. When {@code supported} is false there is no data and every value field is null.
 *
 * @param level      COMFORTABLE, RELAXED, NORMAL, CROWDED or VERY_CROWDED
 * @param levelLabel screen label stored with the level (쾌적, 여유, 보통, 혼잡, 매우 혼잡)
 * @param stops      per-stop congestion in route order. Filled by the DB lookup; empty from the mock and for a
 *                   single stop's own value
 */
public record CongestionResult(boolean supported, Double pct, String level, String levelLabel, CongestionSource source,
                               List<StopCongestion> stops) {

    public CongestionResult {
        boolean anyValue = pct != null || level != null || levelLabel != null || source != null;
        boolean allValues = pct != null && level != null && levelLabel != null && source != null;
        if (supported ? !allValues : anyValue) {
            throw new IllegalArgumentException("supported needs every value, unsupported needs none");
        }
        stops = stops == null ? List.of() : List.copyOf(stops);
    }

    /** Without per-stop values, as before {@code stops} existed. */
    public CongestionResult(boolean supported, Double pct, String level, String levelLabel, CongestionSource source) {
        this(supported, pct, level, levelLabel, source, List.of());
    }

    public static CongestionResult of(double pct, String level, String levelLabel, CongestionSource source) {
        return new CongestionResult(true, pct, level, levelLabel, source);
    }

    public static CongestionResult unsupported() {
        return new CongestionResult(false, null, null, null, null);
    }

    /** Same summary with the given per-stop values. */
    public CongestionResult withStops(List<StopCongestion> stops) {
        return new CongestionResult(supported, pct, level, levelLabel, source, stops);
    }
}
