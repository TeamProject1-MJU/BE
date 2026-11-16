package com.mju.linkro.route.service;

import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.RouteSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.DijkstraSubwayRouteFinder;
import com.mju.linkro.subway.mock.MockFareCalculatorAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.GRAPH;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.ROUTER;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.costs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Plain search alternatives (#153) in RouteSearchService on the hand-made timetable of {@link FirstTrainTimerTest}:
 * 강남(31) -> 고속터미널(40) changes at 교대(32) (W2 14:03, 3호선 14:10). The alternative search is replaced so that
 * the mapping (routeId, routeType, wait), the safety limit and failures can be checked; the first route is always the
 * router's own.
 */
@ExtendWith(OutputCaptureExtension.class)
class RouteSearchTimetableCandidatesTest {
    static final Instant NOW = seoul("2026-11-12T13:00:00");
    static final String REQUESTED = "2026-11-12T14:00:00+09:00";

    /** ROUTER, with the three-argument departingAt answered by {@code avoiding}. */
    static TimetableRouteFinder finder(Function<Set<Long>, Optional<TimedRoute>> avoiding, AtomicInteger calls) {
        return new TimetableRouteFinder() {
            @Override public boolean covers(Instant at) { return ROUTER.covers(at); }
            @Override public boolean noTrainArrivesBefore(Instant at) { return ROUTER.noTrainArrivesBefore(at); }
            @Override public Set<Long> stationIds() { return ROUTER.stationIds(); }
            @Override public void requireStation(long stationId) { ROUTER.requireStation(stationId); }
            @Override public Map<Long, TimedRoute> latestDepartures(long destination, Instant arriveBy, Set<Long> origins, int max) {
                return ROUTER.latestDepartures(destination, arriveBy, origins, max);
            }
            @Override public Map<Long, Instant> earliestArrivalsOfServiceDay(long origin, Instant at) {
                return ROUTER.earliestArrivalsOfServiceDay(origin, at);
            }
            @Override public TimedTree departingAt(long origin, Instant leaveAt) { return ROUTER.departingAt(origin, leaveAt); }
            @Override public TimedTree departingAt(long origin, Instant leaveAt, Set<Long> avoid) {
                calls.incrementAndGet();
                return new TimedTree() {
                    @Override public Set<Long> reachableStationIds() { return Set.of(); }
                    @Override public Optional<TimedRoute> routeTo(long stationId) { return avoiding.apply(avoid); }
                };
            }
        };
    }

    static RouteSearchService service(TimetableRouteFinder finder, int maxCandidates) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(33)).thenReturn(new StationInfo(33, "서초", 37.49, 127.00, true));
        when(stations.requireSupported(40)).thenReturn(new StationInfo(40, "고속터미널", 37.50, 127.00, true));
        KakaoRouteClient kakao = mock(KakaoRouteClient.class);
        when(kakao.search(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new KakaoRouteClient.Failed(KakaoRouteClient.Reason.NO_KEY));
        CongestionLookup congestion = mock(CongestionLookup.class);
        when(congestion.congestionOf(any(), any())).thenReturn(CongestionResult.unsupported());
        InternalRouteBuilder internal = new InternalRouteBuilder(new DijkstraSubwayRouteFinder(GRAPH, costs()),
                MockFareCalculatorAccess.calculator(), Optional.empty());
        return new RouteSearchService(mock(RoomAccess.class), stations, kakao, mock(KakaoRouteMapper.class), internal, congestion,
                Clock.fixed(NOW, ZoneOffset.UTC), FirstTrainTimerTest.TIMER, finder, 365, maxCandidates);
    }

    static RouteSearchResponse search(RouteSearchService service, long destination) {
        return service.search(UUID.randomUUID(), new RouteSearchRequest(31L, destination, REQUESTED, null));
    }

    /** The router's route 강남 -> 고속터미널, five minutes later and on a made-up line so that it is a different route. */
    static TimedRoute laterDifferent() {
        TimedRoute route = ROUTER.departingAt(31, seoul("2026-11-12T14:00:00")).routeTo(40).orElseThrow();
        List<TimedLeg> legs = route.legs().stream().map(leg -> new TimedLeg(leg.lineId() + 100, leg.lineName(), leg.trainCode(),
                leg.terminal(), leg.direction(), leg.fromStationId(), leg.fromStationName(), leg.toStationId(), leg.toStationName(),
                leg.boardAt().plusSeconds(300), leg.alightAt().plusSeconds(300), leg.stationCount(), leg.walkSec(), leg.waitSec())).toList();
        return new TimedRoute(route.path(), legs, route.leaveAt().plusSeconds(300), route.boardAt().plusSeconds(300),
                route.arriveAt().plusSeconds(300));
    }

    @Test
    void aRouteWithoutATransferIsTheOnlyRoute() {
        AtomicInteger calls = new AtomicInteger();
        RouteSearchResponse response = search(service(finder(avoid -> Optional.of(laterDifferent()), calls), 10), 33);
        assertThat(response.routes()).extracting(RouteSearchResponse.Route::routeId).containsExactly("r1");
        assertThat(response.routes().get(0).routeType()).isEqualTo("FASTEST");
        assertThat(calls).hasValue(0);
    }

    @Test
    void anAlternativeIsR2WithItsOwnWaitAndTheLabelsGoByArrival(CapturedOutput output) {
        AtomicInteger calls = new AtomicInteger();
        List<Set<Long>> avoided = new ArrayList<>();
        RouteSearchResponse response = search(service(finder(avoid -> {
            avoided.add(avoid);
            return Optional.of(laterDifferent());
        }, calls), 10), 40);

        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.routes()).extracting(RouteSearchResponse.Route::routeId).containsExactly("r1", "r2");
        TimedRoute first = ROUTER.departingAt(31, seoul("2026-11-12T14:00:00")).routeTo(40).orElseThrow();
        var r1 = response.routes().get(0).summary();
        var r2 = response.routes().get(1).summary();
        assertThat(Instant.parse(r1.arrivalAt())).isEqualTo(first.arriveAt());
        assertThat(Duration.between(Instant.parse(r1.arrivalAt()), Instant.parse(r2.arrivalAt()))).isEqualTo(Duration.ofMinutes(5));
        assertThat(r2.waitSec()).isEqualTo(r1.waitSec() + 300);
        assertThat(r2.totalWithWaitSec()).isEqualTo(r1.totalWithWaitSec() + 300);
        // r1 arrives first and is FASTEST; r2 is no better on transfers or fare, so it has no label.
        assertThat(response.routes()).extracting(RouteSearchResponse.Route::routeType).containsExactly("FASTEST", null);
        // The transfer station of r1 (교대) is avoided; r2 adds nothing new, so the search stops.
        assertThat(avoided).containsExactly(Set.of(32L));
        assertThat(output.getAll()).contains("uses INTERNAL: timetable route from the request time")
                .contains("2 timetable routes (0 repeated, none cut)");
    }

    @Test
    void theSafetyLimitReturnsTheRoutesSoFarAndLogs(CapturedOutput output) {
        RouteSearchResponse response = search(service(finder(avoid -> Optional.of(laterDifferent()), new AtomicInteger()), 1), 40);
        assertThat(response.routes()).extracting(RouteSearchResponse.Route::routeId).containsExactly("r1");
        assertThat(output.getAll()).contains("stopped at 1 timetable routes (linkro.route.max-timetable-candidates)");
    }

    @Test
    void whenTheAlternativesFailTheFirstRouteIsStillReturned(CapturedOutput output) {
        RouteSearchResponse response = search(service(finder(avoid -> {
            throw new IllegalStateException("boom");
        }, new AtomicInteger()), 10), 40);
        assertThat(response.routes()).extracting(RouteSearchResponse.Route::routeId).containsExactly("r1");
        assertThat(response.routes().get(0).routeType()).isEqualTo("FASTEST");
        assertThat(output.getAll()).contains("timetable alternatives failed, returning the first route only");
    }

    @Test
    void theLimitMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(ROUTER, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-timetable-candidates");
    }
}
