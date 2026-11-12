package com.mju.linkro.route.service;

import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.RouteSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.DijkstraSubwayRouteFinder;
import com.mju.linkro.subway.graph.SubwayGraph;
import com.mju.linkro.subway.graph.SubwayGraphProperties;
import com.mju.linkro.subway.graph.TimetableRouter;
import com.mju.linkro.subway.mock.MockFareCalculatorAccess;
import com.mju.linkro.subway.timetable.TimetableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Room appointment mode (#122) on the hand-made timetable of {@link FirstTrainTimerTest}: 2호선 강남(31) -> 교대(32) ->
 * 서초(33). Weekday trains leave 강남 at 05:36 (W1, at 서초 05:40) and 14:03 (W2, at 서초 14:07). 2026-11-12 is a Thursday.
 */
class RouteSearchRoomTimetableTest {
    static final SubwayGraph GRAPH = SubwayGraph.of(FirstTrainTimerTest.DATA);
    static final TimetableRouter ROUTER = new TimetableRouter(GRAPH, FirstTrainTimerTest.timetable(),
            new TimetableClock(new HolidayCalendar("")), costs(), 0, Duration.ofMinutes(60));
    static final Instant NOW = seoul("2026-11-12T02:00:00");

    static SubwayGraphProperties costs() {
        SubwayGraphProperties costs = new SubwayGraphProperties();
        costs.setDwellSeconds(30);
        costs.setTransferWaitSeconds(120);
        costs.setBranchTransferWaitSeconds(120);
        costs.setDefaultBranchTransferSeconds(120);
        return costs;
    }

    /** One service with fresh mocks; {@code router} null is a deployment without the timetable router. */
    record Fixture(RouteSearchService service, KakaoRouteClient kakao, CongestionLookup congestion) {
        RouteSearchResponse search(String departAt, boolean withRoom) {
            return service.search(UUID.randomUUID(), new RouteSearchRequest(31L, 33L, departAt, withRoom ? UUID.randomUUID().toString() : null));
        }
    }

    static Fixture fixture(Instant now, Instant appointment, TimetableRouteFinder router) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(33)).thenReturn(new StationInfo(33, "서초", 37.49, 127.00, true));
        KakaoRouteClient kakao = mock(KakaoRouteClient.class);
        when(kakao.search(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new KakaoRouteClient.Failed(KakaoRouteClient.Reason.NO_KEY));
        CongestionLookup congestion = mock(CongestionLookup.class);
        when(congestion.congestionOf(any(), any())).thenReturn(CongestionResult.unsupported());
        MeetingRoom room = new MeetingRoom("ABC123", "방", UUID.randomUUID());
        if (appointment != null) room.schedule(appointment);
        RoomAccess rooms = mock(RoomAccess.class);
        when(rooms.requireMember(any(), any())).thenReturn(room);
        InternalRouteBuilder internal = new InternalRouteBuilder(new DijkstraSubwayRouteFinder(GRAPH, costs()),
                MockFareCalculatorAccess.calculator(), Optional.empty());
        RouteSearchService service = new RouteSearchService(rooms, stations, kakao, mock(KakaoRouteMapper.class), internal, congestion,
                Clock.fixed(now, ZoneOffset.UTC), FirstTrainTimerTest.TIMER, router);
        return new Fixture(service, kakao, congestion);
    }

    static String seoulOffset(Instant at) {
        return at.atZone(java.time.ZoneId.of("Asia/Seoul")).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static List<Long> stationOrder(List<PathStop> stops) {
        List<Long> ids = new ArrayList<>();
        stops.forEach(stop -> { if (ids.isEmpty() || ids.get(ids.size() - 1) != stop.stationId()) ids.add(stop.stationId()); });
        return ids;
    }

    static List<Long> stationOrder(RouteSearchResponse.Route route) {
        List<Long> ids = new ArrayList<>();
        route.steps().forEach(step -> step.stops().forEach(stop -> { if (ids.isEmpty() || ids.get(ids.size() - 1) != stop.id()) ids.add(stop.id()); }));
        return ids;
    }

    @Test
    void anActiveRoomAppointmentUsesTheRecommendationRouteWithoutKakao() {
        Instant appointment = seoul("2026-11-12T14:07:00");
        // What the engine computes: one search for all member origins (강남 and 교대).
        TimedRoute recommended = ROUTER.latestDepartures(33, appointment, Set.of(31L, 32L), 0).get(31L);
        Fixture f = fixture(NOW, appointment, ROUTER);

        RouteSearchResponse response = f.search("2026-11-12T20:00:00+09:00", true);

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.appointmentPassed()).isFalse();
        assertThat(response.notice()).isNull();
        assertThat(response.routes()).hasSize(1);
        var route = response.routes().get(0);
        var summary = route.summary();
        assertThat(summary.departureAt()).isEqualTo(seoulOffset(recommended.leaveAt())).isEqualTo("2026-11-12T14:03:00+09:00");
        // Arriving exactly at the appointment is in time.
        assertThat(summary.arrivalAt()).isEqualTo(seoulOffset(recommended.arriveAt())).isEqualTo("2026-11-12T14:07:00+09:00");
        assertThat(summary.totalTimeSec()).isEqualTo(Duration.between(recommended.leaveAt(), recommended.arriveAt()).toSeconds()).isEqualTo(240);
        assertThat(stationOrder(route)).isEqualTo(stationOrder(recommended.path().stops())).containsExactly(31L, 32L, 33L);
        assertThat(summary.arrivesAfterAppointment()).isFalse();
        assertThat(summary.noService()).isFalse();
        assertThat(summary.serviceNotice()).isNull();
        // Stop congestion counts from the first boarding, as recommendations do.
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(f.congestion()).congestionOf(any(), from.capture());
        assertThat(from.getValue()).isEqualTo(recommended.boardAt());
    }

    @Test
    void oneSecondBeforeTheArrivalTakesTheEarlierTrain() {
        // W2 reaches 서초 at 14:07:00, one second late for 14:06:59: the latest train in time is W1 (05:36 -> 05:40).
        Instant appointment = seoul("2026-11-12T14:06:59");
        var summary = fixture(NOW, appointment, ROUTER).search(null, true).routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-12T05:40:00+09:00");
        assertThat(seoul("2026-11-12T05:40:00")).isBeforeOrEqualTo(appointment);
        assertThat(summary.arrivesAfterAppointment()).isFalse();
    }

    @Test
    void noTrainInTimeFallsBackToTheFlowWithoutTheRouter() {
        // 05:39:59: the first train reaches 서초 at 05:40, so the timetable has no route; the response is the #120 one.
        Instant appointment = seoul("2026-11-12T05:39:59");
        Fixture withRouter = fixture(NOW, appointment, ROUTER);
        RouteSearchResponse response = withRouter.search(null, true);
        assertThat(response).isEqualTo(fixture(NOW, appointment, null).search(null, true));
        verify(withRouter.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(response.routes().get(0).summary().noService()).isTrue();
        assertThat(response.routes().get(0).summary().arrivesAfterAppointment()).isTrue();
    }

    @Test
    void aPassedAppointmentNoRoomAndNoRouterAnswerAsBefore() {
        Instant afternoon = seoul("2026-11-12T14:00:00");
        // Appointment passed: the request time, as before (#120).
        Instant passed = seoul("2026-11-12T13:00:00");
        RouteSearchResponse passedResponse = fixture(afternoon, passed, ROUTER).search(null, true);
        assertThat(passedResponse).isEqualTo(fixture(afternoon, passed, null).search(null, true));
        assertThat(passedResponse.appointmentPassed()).isTrue();
        // No room: departAt, as before.
        Fixture alone = fixture(afternoon, seoul("2026-11-12T14:07:00"), ROUTER);
        assertThat(alone.search("2026-11-12T14:00:00+09:00", false))
                .isEqualTo(fixture(afternoon, seoul("2026-11-12T14:07:00"), null).search("2026-11-12T14:00:00+09:00", false));
        verify(alone.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        // A room without an appointment: as before.
        assertThat(fixture(afternoon, null, ROUTER).search(null, true)).isEqualTo(fixture(afternoon, null, null).search(null, true));
        // No router (timetable disabled): an active appointment still uses Kakao/INTERNAL with appointment − totalTimeSec.
        Fixture noRouter = fixture(NOW, seoul("2026-11-12T14:07:00"), null);
        RouteSearchResponse fixed = noRouter.search(null, true);
        verify(noRouter.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(fixed.routes().get(0).summary().arrivalAt()).isEqualTo("2026-11-12T14:07:00+09:00");
    }
}
