package com.mju.linkro.subway.api;

/**
 * Congestion of a route. When {@code supported} is false there is no data and every other field is null.
 *
 * @param level      COMFORTABLE, RELAXED, NORMAL, CROWDED or VERY_CROWDED
 * @param levelLabel screen label stored with the level (쾌적, 여유, 보통, 혼잡, 매우 혼잡)
 */
public record CongestionResult(boolean supported, Double pct, String level, String levelLabel, CongestionSource source) {

    public CongestionResult {
        boolean anyValue = pct != null || level != null || levelLabel != null || source != null;
        boolean allValues = pct != null && level != null && levelLabel != null && source != null;
        if (supported ? !allValues : anyValue) {
            throw new IllegalArgumentException("supported needs every value, unsupported needs none");
        }
    }

    public static CongestionResult of(double pct, String level, String levelLabel, CongestionSource source) {
        return new CongestionResult(true, pct, level, levelLabel, source);
    }

    public static CongestionResult unsupported() {
        return new CongestionResult(false, null, null, null, null);
    }
}
