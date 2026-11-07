package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.SeriesKey;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository.Stat;
import com.mju.linkro.subway.mock.MockSubwayRouteFinder;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

class DbCongestionLookupTest {

    @Test
    void oneQueryForTheWholeRoute() {
        CongestionStatsRepository stats = mock(CongestionStatsRepository.class);
        when(stats.series(anyCollection())).thenReturn(Map.of());
        var lookup = new DbCongestionLookup(stats, new CongestionResolver(new HolidayCalendar("")));
        // 61 stops on one line.
        var path = new MockSubwayRouteFinder().shortestPath(1, 61);

        CongestionResult result = lookup.congestionOf(path, Instant.parse("2026-11-06T00:00:00Z"));

        verify(stats, times(1)).series(anyCollection());
        assertThat(result.supported()).isFalse();
        assertThat(result.stops()).hasSize(61);
    }

    @Test
    void summaryPicksTheHighestRawValueNotTheHighestShownValue() {
        // Stop 1 (first) 34.6 PREDICTED and stop 2 34.95 ESTIMATED both show 34. The raw maximum is stop 2, so the
        // summary is ESTIMATED; comparing shown values would keep the first stop (PREDICTED).
        LocalTime slot = LocalTime.of(8, 0);
        CongestionStatsRepository stats = mock(CongestionStatsRepository.class);
        when(stats.series(anyCollection())).thenReturn(Map.of(
                new SeriesKey(1, DirectionCode.DOWN, DayType.WEEKDAY),
                Map.of(slot, new Stat(slot, CongestionSource.PREDICTED, new BigDecimal("34.6"), "COMFORTABLE", "쾌적")),
                new SeriesKey(2, DirectionCode.DOWN, DayType.WEEKDAY),
                Map.of(slot, new Stat(slot, CongestionSource.ESTIMATED, new BigDecimal("34.95"), "COMFORTABLE", "쾌적")),
                new SeriesKey(3, DirectionCode.DOWN, DayType.WEEKDAY),
                Map.of(slot, new Stat(slot, CongestionSource.PREDICTED, new BigDecimal("12.5"), "COMFORTABLE", "쾌적"))));
        var lookup = new DbCongestionLookup(stats, new CongestionResolver(new HolidayCalendar("")));
        // Stations 1..3 on the mock line, 90 s apart: all within Friday 2026-11-06 08:00~08:29 (Asia/Seoul).
        var path = new MockSubwayRouteFinder().shortestPath(1, 3);

        CongestionResult result = lookup.congestionOf(path, Instant.parse("2026-11-05T23:00:00Z"));

        assertThat(result.stops()).extracting(stop -> stop.congestion().pct()).containsExactly(34.0, 34.0, 13.0);
        assertThat(result).extracting(CongestionResult::pct, CongestionResult::source).containsExactly(34.0, CongestionSource.ESTIMATED);
    }

    @Test
    void anEmptyRouteNeedsNoQuery() {
        CongestionStatsRepository stats = mock(CongestionStatsRepository.class);
        when(stats.series(anyCollection())).thenReturn(Map.of());
        var lookup = new DbCongestionLookup(stats, new CongestionResolver(new HolidayCalendar("")));

        CongestionResult result = lookup.congestionOf(new MockSubwayRouteFinder().shortestPath(5, 5), Instant.now());

        assertThat(result.supported()).isFalse();
        assertThat(result.stops()).singleElement().satisfies(stop -> assertThat(stop.direction()).isNull());
        verify(stats).series(argThat(keys -> keys.isEmpty()));
    }
}
