package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.RoutePath;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic fake congestion: each stop is {@code 40 + (stationId % 7) * 15} percent and the route takes the
 * highest. Time is ignored. The real module reads the stored level instead of computing it.
 */
public class MockCongestionLookup implements CongestionLookup {

    @Override
    public CongestionResult congestionOf(RoutePath path, Instant departAt) {
        Objects.requireNonNull(departAt, "departAt");
        double pct = path.stops().stream().mapToDouble(MockCongestionLookup::pctOf).max().orElseThrow();
        return CongestionResult.of(pct, levelOf(pct), labelOf(levelOf(pct)), CongestionSource.MOCK);
    }

    static double pctOf(PathStop stop) {
        return 40 + (stop.stationId() % 7) * 15;
    }

    /** 6.2 levels: lower bound inclusive, upper bound exclusive (35 / 50 / 100 / 150). */
    static String levelOf(double pct) {
        if (pct < 35) {
            return "COMFORTABLE";
        }
        if (pct < 50) {
            return "RELAXED";
        }
        if (pct < 100) {
            return "NORMAL";
        }
        return pct < 150 ? "CROWDED" : "VERY_CROWDED";
    }

    static String labelOf(String level) {
        return switch (level) {
            case "COMFORTABLE" -> "쾌적";
            case "RELAXED" -> "여유";
            case "NORMAL" -> "보통";
            case "CROWDED" -> "혼잡";
            case "VERY_CROWDED" -> "매우 혼잡";
            default -> throw new IllegalArgumentException("unknown level " + level);
        };
    }
}
