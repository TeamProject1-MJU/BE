package com.mju.linkro.subway.congestion.load;

import java.math.BigDecimal;

/** Level and label for a computed pct; the bounds are those of ck_cong_level (V10). */
final class CongestionLevels {
    record Level(String code, String label) {}

    private static final BigDecimal RELAXED = BigDecimal.valueOf(35);
    private static final BigDecimal NORMAL = BigDecimal.valueOf(50);
    private static final BigDecimal CROWDED = BigDecimal.valueOf(100);
    private static final BigDecimal VERY_CROWDED = BigDecimal.valueOf(150);

    private CongestionLevels() {}

    static Level of(BigDecimal pct) {
        if (pct.compareTo(RELAXED) < 0) return new Level("COMFORTABLE", "쾌적");
        if (pct.compareTo(NORMAL) < 0) return new Level("RELAXED", "여유");
        if (pct.compareTo(CROWDED) < 0) return new Level("NORMAL", "보통");
        if (pct.compareTo(VERY_CROWDED) < 0) return new Level("CROWDED", "혼잡");
        return new Level("VERY_CROWDED", "매우 혼잡");
    }
}
