package com.mju.linkro.subway.congestion;

import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.congestion.repository.CongestionStatsRepository;
import com.mju.linkro.subway.mock.MockSubwayRouteFinder;
import java.time.Instant;
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
