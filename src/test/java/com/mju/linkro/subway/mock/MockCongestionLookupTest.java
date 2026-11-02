package com.mju.linkro.subway.mock;

import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.CongestionSource;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MockCongestionLookupTest {
    private final MockCongestionLookup lookup = new MockCongestionLookup();

    @ParameterizedTest
    @CsvSource({"0.0,COMFORTABLE,쾌적", "34.9,COMFORTABLE,쾌적", "35.0,RELAXED,여유", "49.9,RELAXED,여유",
        "50.0,NORMAL,보통", "99.9,NORMAL,보통", "100.0,CROWDED,혼잡", "149.9,CROWDED,혼잡",
        "150.0,VERY_CROWDED,매우 혼잡", "220.0,VERY_CROWDED,매우 혼잡"})
    void levelBoundsAreLowerInclusive(double pct, String level, String label) {
        assertThat(MockCongestionLookup.levelOf(pct)).isEqualTo(level);
        assertThat(MockCongestionLookup.labelOf(level)).isEqualTo(label);
    }

    @Test
    void routeTakesTheHighestStop() {
        // Stops 3..7: station 6 gives 40 + 6 * 15 = 130, the highest.
        var path = new MockSubwayRouteFinder().shortestPath(3, 7);

        CongestionResult result = lookup.congestionOf(path, Instant.parse("2026-11-02T09:00:00Z"));

        assertThat(result).isEqualTo(CongestionResult.of(130.0, "CROWDED", "혼잡", CongestionSource.MOCK));
    }
}
