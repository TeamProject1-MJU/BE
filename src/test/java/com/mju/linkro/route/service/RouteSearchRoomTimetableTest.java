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
    void withoutTheRouterAnActiveAppointmentAnswersAsBefore() {
        // A passed appointment, no room and a room without an appointment are solo mode (#130, RouteSearchSoloTimetableTest).
        // No router (timetable disabled): an active appointment still uses Kakao/INTERNAL with appointment − totalTimeSec.
        Fixture noRouter = fixture(NOW, seoul("2026-11-12T14:07:00"), null);
        RouteSearchResponse fixed = noRouter.search(null, true);
        verify(noRouter.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(fixed.routes().get(0).summary().arrivalAt()).isEqualTo("2026-11-12T14:07:00+09:00");
    }

    // ---- #140: the latest departure for the appointment has already left ----

    static RouteSearchResponse roomSearch(RouteSearchSoloTimetableTest.Fixture f, long origin, long destination) {
        return f.search(origin, destination, null, true);
    }

    @Test
    void theLatestDepartureHasLeftSoTheRouteLeavesNowAndArrivesLate() {
        // (b) 14:04, appointment 14:09: W2 (14:03 -> 14:07) has left; from now the next train is W3 (23:33 -> 23:37).
        Instant now = seoul("2026-11-12T14:04:00");
        var f = RouteSearchSoloTimetableTest.fixture(now, seoul("2026-11-12T14:09:00"), ROUTER);
        RouteSearchResponse response = roomSearch(f, 31, 33);

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.appointmentPassed()).isFalse();
        assertThat(response.notice()).isNull();
        var summary = response.routes().get(0).summary();
        TimedRoute fromNow = ROUTER.departingAt(31, now).routeTo(33).orElseThrow();
        assertThat(summary.departureAt()).isEqualTo(seoulOffset(fromNow.leaveAt())).isEqualTo("2026-11-12T23:33:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo(seoulOffset(fromNow.arriveAt())).isEqualTo("2026-11-12T23:37:00+09:00");
        assertThat(summary.totalTimeSec()).isEqualTo(240);
        assertThat(summary.arrivesAfterAppointment()).isTrue();
        assertThat(summary.waitSec()).isEqualTo((int) Duration.between(now, fromNow.leaveAt()).toSeconds()).isEqualTo(34140);
        assertThat(summary.waitKind()).isEqualTo("TIMETABLE");
        assertThat(summary.totalWithWaitSec()).isEqualTo(34380);
        assertThat(summary.slackSeconds()).isNull();
        assertThat(summary.noService()).isFalse();
    }

    @Test
    void aroundMidnightTheRouteFromNowTakesTheLastTrainAndArrivesLate() {
        // (c-2) 23:50, appointment 00:10: W3 (23:33) has left; from now W4 (24:46 -> 24:50).
        Instant now = seoul("2026-11-12T23:50:00");
        var f = RouteSearchSoloTimetableTest.fixture(now, seoul("2026-11-13T00:10:00"), ROUTER);
        var summary = roomSearch(f, 31, 33).routes().get(0).summary();

        verifyNoInteractions(f.kakao());
        assertThat(summary.departureAt()).isEqualTo("2026-11-13T00:46:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-13T00:50:00+09:00");
        assertThat(summary.arrivesAfterAppointment()).isTrue();
        assertThat(summary.waitSec()).isEqualTo(56 * 60);
        assertThat(summary.slackSeconds()).isNull();
    }

    @Test
    void theRouteFromNowAlwaysArrivesAfterTheAppointment() {
        // Whenever the latest departure has left, nothing leaving now arrives in time.
        String[][] cases = {{"2026-11-12T14:04:00", "2026-11-12T14:09:00"}, {"2026-11-12T14:03:01", "2026-11-12T14:07:00"},
                {"2026-11-12T23:34:00", "2026-11-12T23:40:00"}, {"2026-11-12T23:50:00", "2026-11-13T00:10:00"},
                {"2026-11-12T05:37:00", "2026-11-12T05:41:00"}};
        for (String[] c : cases) {
            Instant now = seoul(c[0]);
            assertThat(ROUTER.latestDepartures(33, seoul(c[1]), Set.of(31L), 0).get(31L).leaveAt()).as(c[0]).isBefore(now);
            var summary = roomSearch(RouteSearchSoloTimetableTest.fixture(now, seoul(c[1]), ROUTER), 31, 33).routes().get(0).summary();
            assertThat(summary.arrivesAfterAppointment()).as(c[0]).isTrue();
            assertThat(summary.waitKind()).as(c[0]).isEqualTo("TIMETABLE");
        }
    }

    @Test
    void leavingInTheSameSecondIsStillTheRoomRoute() {
        // W2 leaves at 14:03:00: now 14:03:00.9 keeps it (no wait, in time); one second later the route leaves from now.
        Instant appointment = seoul("2026-11-12T14:09:00");
        var same = roomSearch(RouteSearchSoloTimetableTest.fixture(seoul("2026-11-12T14:03:00").plusMillis(900), appointment, ROUTER), 31, 33)
                .routes().get(0).summary();
        assertThat(same.departureAt()).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(same.arrivesAfterAppointment()).isFalse();
        assertThat(same.waitSec()).isNull();
        var late = roomSearch(RouteSearchSoloTimetableTest.fixture(seoul("2026-11-12T14:03:01"), appointment, ROUTER), 31, 33)
                .routes().get(0).summary();
        assertThat(late.departureAt()).isEqualTo("2026-11-12T23:33:00+09:00");
        assertThat(late.arrivesAfterAppointment()).isTrue();
    }

    @Test
    void noRouteFromNowEitherFallsBackToTheFlowWithoutTheRouter() {
        // Friday 2026-11-13, 강남 -> 고속터미널 for 23:59: the latest departure is W2 14:03 (T2 to 14:12), long gone at 23:40.
        // From now no 3호선 train is left that day and Saturday has none: Kakao/INTERNAL as without the router.
        Instant now = seoul("2026-11-13T23:40:00");
        Instant appointment = seoul("2026-11-13T23:59:00");
        assertThat(ROUTER.latestDepartures(40, appointment, Set.of(31L), 0).get(31L).leaveAt()).isEqualTo(seoul("2026-11-13T14:03:00"));
        var withRouter = RouteSearchSoloTimetableTest.fixture(now, appointment, ROUTER);

        RouteSearchResponse response = roomSearch(withRouter, 31, 40);

        assertThat(response).isEqualTo(roomSearch(RouteSearchSoloTimetableTest.fixture(now, appointment, null), 31, 40));
        verify(withRouter.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(response.routes().get(0).summary().waitSec()).isNull();
    }
}
