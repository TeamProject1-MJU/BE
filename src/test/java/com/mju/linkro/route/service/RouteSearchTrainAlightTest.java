package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.Step;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static com.mju.linkro.route.service.RouteSearchPlainTimetableTest.fixture;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.ROUTER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * steps[].trainAlightStationId (#174) on the hand-made timetable of {@link FirstTrainTimerTest} (one train 강남 31 -> 서초 33):
 * every timetable mode gives the first train's alighting station, routes without the timetable router give null.
 */
class RouteSearchTrainAlightTest {
    static final Instant NOW = seoul("2026-11-12T02:00:00");

    static List<Long> subwayAlights(RouteSearchResponse response) {
        List<Step> steps = response.routes().get(0).steps();
        assertThat(steps).filteredOn(step -> !"SUBWAY".equals(step.type())).allSatisfy(step -> assertThat(step.trainAlightStationId()).isNull());
        return steps.stream().filter(step -> "SUBWAY".equals(step.type())).map(Step::trainAlightStationId).toList();
    }

    @Test
    void plainSearchGivesTheFirstTrainsAlightingStation() {
        var f = fixture(seoul("2026-11-12T14:00:00"), null, ROUTER);
        assertThat(subwayAlights(f.search("2026-11-12T14:00:00+09:00"))).containsExactly(33L);
    }

    @Test
    void arrivalModeAndRoomModeGiveItToo() {
        var arrival = fixture(NOW, null, ROUTER).service().search(UUID.randomUUID(),
                new RouteSearchRequest(31L, 33L, null, null, "2026-11-12T14:10:00+09:00"));
        assertThat(subwayAlights(arrival)).containsExactly(33L);
        var room = fixture(NOW, seoul("2026-11-12T14:07:00"), ROUTER);
        assertThat(subwayAlights(room.search(31, 33, null, true))).containsExactly(33L);
    }

    @Test
    void withoutTheTimetableRouterItIsNull() {
        // No router and no Kakao key: the fixed-cost INTERNAL route.
        var f = fixture(NOW, null, null);
        assertThat(subwayAlights(f.search("2026-11-12T14:00:00+09:00"))).isNotEmpty().containsOnlyNulls();
    }
}
