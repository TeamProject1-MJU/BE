package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSearchServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");

    private static RouteDraft draft(int time, int transfers, int fare) {
        return new RouteDraft(List.of(), time, fare, FareSource.KAKAO, transfers, 1, 2);
    }

    @Test
    void eachLabelGoesToTheFirstBestRoute() {
        assertThat(RouteSearchService.routeTypes(List.of(draft(900, 1, 1550), draft(1000, 0, 1550), draft(1100, 0, 1450))))
                .containsExactly("FASTEST", "FEWER_TRANSFERS", "CHEAPEST");
        // The first route is best on every count: it keeps FASTEST and a tied later route gets no label.
        assertThat(RouteSearchService.routeTypes(List.of(draft(900, 0, 1450), draft(1000, 0, 1450))))
                .containsExactly("FASTEST", null);
        assertThat(RouteSearchService.routeTypes(List.of(draft(900, 0, 1450)))).containsExactly("FASTEST");
    }

    @Test
    void departureRule() {
        Instant departAt = Instant.parse("2026-11-06T09:00:00Z");
        assertThat(RouteSearchService.departure(null, null, 600, NOW)).isEqualTo(NOW);
        assertThat(RouteSearchService.departure(null, departAt, 600, NOW)).isEqualTo(departAt);
    }
}
