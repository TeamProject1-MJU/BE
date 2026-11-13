package com.mju.linkro.route.service;

import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.RouteSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.DijkstraSubwayRouteFinder;
import com.mju.linkro.subway.mock.MockFareCalculatorAccess;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.GRAPH;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.ROUTER;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.costs;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.seoulOffset;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.stationOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Solo mode (#130) on the hand-made timetable of {@link FirstTrainTimerTest}: 2호선 강남(31) -> 교대(32) -> 서초(33),
 * weekday trains from 강남 at 05:36 (W1), 14:03 (W2), 23:33 (W3) and 24:46 (W4), all 4 minutes to 서초; 3호선 교대(32) ->
 * 고속터미널(40) weekdays at 05:50, 14:10 and 24:20 only; Saturday has one 2호선 train (05:35) and no 3호선 train.
 * 2026-11-12 is a Thursday, 2026-11-14 a Saturday.
 */
class RouteSearchSoloTimetableTest {
    static final Instant AFTERNOON = seoul("2026-11-12T14:00:00");

    record Fixture(RouteSearchService service, KakaoRouteClient kakao, CongestionLookup congestion) {
        RouteSearchResponse search(long origin, long destination, String departAt, boolean withRoom) {
            return service.search(UUID.randomUUID(),
                    new RouteSearchRequest(origin, destination, departAt, withRoom ? UUID.randomUUID().toString() : null));
        }

        RouteSearchResponse search(String departAt) {
            return search(31, 33, departAt, false);
        }
    }

    /** {@code router} null is a deployment without the timetable router; {@code appointment} null a room without one. */
    static Fixture fixture(Instant now, Instant appointment, TimetableRouteFinder router) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(32)).thenReturn(new StationInfo(32, "교대", 37.49, 127.01, true));
        when(stations.requireSupported(33)).thenReturn(new StationInfo(33, "서초", 37.49, 127.00, true));
        when(stations.requireSupported(40)).thenReturn(new StationInfo(40, "고속터미널", 37.50, 127.00, true));
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

    @Test
    void noRoomRidesTheRecommendationRouteFromTheRequestTimeWithoutKakao() {
        Instant requested = seoul("2026-11-12T14:00:00");
        // What the engine computes for a member without an appointment.
        TimedRoute recommended = ROUTER.departingAt(31, requested).routeTo(33).orElseThrow();
        Fixture f = fixture(AFTERNOON, null, ROUTER);

        RouteSearchResponse response = f.search("2026-11-12T14:00:00+09:00");

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.appointmentPassed()).isFalse();
        assertThat(response.notice()).isNull();
        assertThat(response.routes()).hasSize(1);
        var route = response.routes().get(0);
        assertThat(route.routeType()).isEqualTo("FASTEST");
        var summary = route.summary();
        assertThat(summary.fareSource()).isEqualTo(FareSource.ESTIMATED);
        // The next train is W2 at 14:03: leaving is the boarding (no station access time), three minutes after the request.
        assertThat(summary.departureAt()).isEqualTo(seoulOffset(recommended.leaveAt())).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo(seoulOffset(recommended.arriveAt())).isEqualTo("2026-11-12T14:07:00+09:00");
        assertThat(summary.totalTimeSec()).isEqualTo(Duration.between(recommended.leaveAt(), recommended.arriveAt()).toSeconds()).isEqualTo(240);
        assertThat(summary.waitSec()).isEqualTo(180);
        assertThat(summary.waitKind()).isEqualTo("TIMETABLE");
        assertThat(summary.totalWithWaitSec()).isEqualTo(420).isEqualTo(summary.waitSec() + summary.totalTimeSec());
        assertThat(summary.transferCount()).isEqualTo(recommended.path().transferCount());
        assertThat(stationOrder(route)).isEqualTo(stationOrder(recommended.path().stops())).containsExactly(31L, 32L, 33L);
        // W2 is not the day's first train.
        assertThat(summary.noService()).isFalse();
        assertThat(summary.serviceNotice()).isNull();
        assertThat(summary.arrivesAfterAppointment()).isFalse();
        // Stop congestion counts from the first boarding, as recommendations do.
        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(f.congestion()).congestionOf(any(), from.capture());
        assertThat(from.getValue()).isEqualTo(recommended.boardAt());
    }

    @Test
    void withoutDepartAtTheRequestTimeIsNow() {
        var summary = fixture(AFTERNOON, null, ROUTER).search(null).routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(summary.waitSec()).isEqualTo(180);
    }

    @Test
    void beforeTheFirstTrainIsNoServiceWithTheFirstTrainNotice() {
        Fixture f = fixture(AFTERNOON, null, ROUTER);
        var summary = f.search("2026-11-12T03:00:00+09:00").routes().get(0).summary();

        verifyNoInteractions(f.kakao());
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-12T05:40:00+09:00");
        assertThat(summary.totalTimeSec()).isEqualTo(240);
        assertThat(summary.waitSec()).isEqualTo(2 * 3600 + 36 * 60);
        assertThat(summary.totalWithWaitSec()).isEqualTo(2 * 3600 + 40 * 60);
        assertThat(summary.noService()).isTrue();
        assertThat(summary.serviceNotice()).isEqualTo(RouteSearchService.serviceNotice(seoul("2026-11-12T05:36:00")))
                .contains("첫차(11월 12일 05:36 출발)");
    }

    @Test
    void theFirstTrainOnTheDotIsNotNoService() {
        // As #120: asking at the first train's departure catches it.
        var summary = fixture(AFTERNOON, null, ROUTER).search("2026-11-12T05:36:00+09:00").routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.waitSec()).isZero();
        assertThat(summary.noService()).isFalse();
    }

    @Test
    void afterTheRoutesLastTrainItIsSearchedAgainFromTheNextServiceDay() {
        // Thursday's service day, 00:30 on Friday: W4 (24:46) still runs, so the router stays on Thursday, but the last
        // 3호선 train (24:20) has left 교대: no route that day.
        Instant requested = seoul("2026-11-13T00:30:00");
        assertThat(ROUTER.departingAt(31, requested).routeTo(40)).isEmpty();
        Fixture f = fixture(AFTERNOON, null, ROUTER);

        RouteSearchResponse response = f.search(31, 40, "2026-11-13T00:30:00+09:00", false);

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        var summary = response.routes().get(0).summary();
        // Friday's first trains: W1 05:36 to 교대 05:38, a 60 s walk, T1 05:50 to 고속터미널 05:52.
        TimedRoute friday = ROUTER.departingAt(31, seoul("2026-11-13T03:00:00")).routeTo(40).orElseThrow();
        assertThat(summary.departureAt()).isEqualTo(seoulOffset(friday.leaveAt())).isEqualTo("2026-11-13T05:36:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo(seoulOffset(friday.arriveAt())).isEqualTo("2026-11-13T05:52:00+09:00");
        assertThat(summary.transferCount()).isEqualTo(1);
        assertThat(summary.noService()).isTrue();
        assertThat(summary.serviceNotice()).contains("첫차(11월 13일 05:36 출발)");
        assertThat(summary.waitSec()).isEqualTo((int) Duration.between(requested, friday.leaveAt()).toSeconds());
        assertThat(summary.totalWithWaitSec()).isEqualTo((int) Duration.between(requested, friday.arriveAt()).toSeconds());
    }

    @Test
    void afterTheDaysLastDepartureTheRouterAlreadyTakesTheNextDay() {
        // 00:55 on Friday is after every Thursday departure (last 24:48 at 교대): the router itself searches Friday.
        var summary = fixture(AFTERNOON, null, ROUTER).search("2026-11-13T00:55:00+09:00").routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-13T05:36:00+09:00");
        assertThat(summary.noService()).isTrue();
    }

    @Test
    void noRouteOnTheNextServiceDayEitherFallsBackAsBefore() {
        // Friday 23:40: no 3호선 train is left, and Saturday has none at all: the flow without the timetable router.
        Fixture solo = fixture(AFTERNOON, null, ROUTER);
        RouteSearchResponse response = solo.search(31, 40, "2026-11-13T23:40:00+09:00", false);

        assertThat(response).isEqualTo(fixture(AFTERNOON, null, null).search(31, 40, "2026-11-13T23:40:00+09:00", false));
        verify(solo.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(response.routes().get(0).summary().waitSec()).isNull();
        assertThat(response.routes().get(0).summary().waitKind()).isNull();
        assertThat(response.routes().get(0).summary().totalWithWaitSec()).isNull();
    }

    @Test
    void aPassedAppointmentKeepsItsNoticeAndLeavesNow() {
        // 14:00 now, appointment 13:00: departAt (20:00) is ignored and the route leaves from now, like #120.
        Fixture f = fixture(AFTERNOON, seoul("2026-11-12T13:00:00"), ROUTER);
        RouteSearchResponse response = f.search(31, 33, "2026-11-12T20:00:00+09:00", true);

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.appointmentPassed()).isTrue();
        assertThat(response.notice()).isEqualTo(RouteSearchService.APPOINTMENT_PASSED_NOTICE);
        var summary = response.routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(summary.waitSec()).isEqualTo(180);
        assertThat(summary.arrivesAfterAppointment()).isFalse();
    }

    @Test
    void aRoomWithoutAnAppointmentIsSoloFromDepartAt() {
        Fixture f = fixture(AFTERNOON, null, ROUTER);
        RouteSearchResponse response = f.search(31, 33, "2026-11-12T05:00:00+09:00", true);

        verifyNoInteractions(f.kakao());
        assertThat(response.appointmentPassed()).isFalse();
        assertThat(response.notice()).isNull();
        var summary = response.routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.waitSec()).isEqualTo(36 * 60);
        assertThat(summary.noService()).isTrue();
    }

    @Test
    void anActiveAppointmentIsRoomModeWithoutWaitFields() {
        var summary = fixture(seoul("2026-11-12T02:00:00"), seoul("2026-11-12T14:07:00"), ROUTER).search(31, 33, null, true)
                .routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(summary.waitSec()).isNull();
        assertThat(summary.waitKind()).isNull();
        assertThat(summary.totalWithWaitSec()).isNull();
    }

    @Test
    void withoutTheRouterNothingChanges() {
        // No router (mock module, timetable disabled): the same answer as before #130, Kakao first.
        Fixture noRouter = fixture(AFTERNOON, null, null);
        RouteSearchResponse response = noRouter.search("2026-11-12T14:00:00+09:00");
        verify(noRouter.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        var summary = response.routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T14:00:00+09:00");
        assertThat(summary.waitSec()).isNull();
        assertThat(summary.waitKind()).isNull();
        assertThat(summary.totalWithWaitSec()).isNull();
    }

    @Test
    void theSameStationIsAnsweredAsBefore() {
        Fixture solo = fixture(AFTERNOON, null, ROUTER);
        RouteSearchResponse response = solo.search(31, 31, "2026-11-12T14:00:00+09:00", false);
        verifyNoInteractions(solo.kakao());
        assertThat(response).isEqualTo(fixture(AFTERNOON, null, null).search(31, 31, "2026-11-12T14:00:00+09:00", false));
    }
}
