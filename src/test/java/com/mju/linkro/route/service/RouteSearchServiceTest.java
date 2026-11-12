package com.mju.linkro.route.service;

import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void appointmentPassedOnlyWhenTheAppointmentItselfIsBeforeNowInWholeSeconds() {
        MeetingRoom room = new MeetingRoom("ABC123", "방", UUID.randomUUID());
        assertThat(RouteSearchService.appointmentPassed(null, NOW)).isFalse();
        assertThat(RouteSearchService.appointmentPassed(room, NOW)).isFalse();
        room.schedule(NOW.minusSeconds(1));
        assertThat(RouteSearchService.appointmentPassed(room, NOW)).isTrue();
        // The same second is not passed (as APPOINTMENT_PASSED of recommendations).
        room.schedule(NOW.plusMillis(100));
        assertThat(RouteSearchService.appointmentPassed(room, NOW.plusMillis(900))).isFalse();
        // Planned departure (appointment − totalTimeSec) passed but the appointment not: not reported (known limit).
        room.schedule(NOW.plusSeconds(60));
        assertThat(RouteSearchService.appointmentPassed(room, NOW)).isFalse();
    }

    // ---- whole responses with the timetable of FirstTrainTimerTest (INTERNAL 강남 -> 서초) ----

    private static RouteSearchService service(Instant now, FirstTrainTimer timer, RoomAccess rooms) {
        return service(now, timer, rooms, FirstTrainTimerTest.gangnamToSeocho());
    }

    private static RouteSearchService service(Instant now, FirstTrainTimer timer, RoomAccess rooms, RouteDraft route) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(33)).thenReturn(new StationInfo(33, "서초", 37.49, 127.00, true));
        KakaoRouteClient kakao = mock(KakaoRouteClient.class);
        when(kakao.search(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new KakaoRouteClient.Failed(KakaoRouteClient.Reason.NO_KEY));
        InternalRouteBuilder internal = mock(InternalRouteBuilder.class);
        when(internal.build(31, 33)).thenReturn(route);
        CongestionLookup congestion = mock(CongestionLookup.class);
        when(congestion.congestionOf(any(), any())).thenReturn(CongestionResult.unsupported());
        return new RouteSearchService(rooms, stations, kakao, mock(KakaoRouteMapper.class), internal, congestion,
                Clock.fixed(now, ZoneOffset.UTC), timer);
    }

    private static RouteSearchResponse search(Instant now, FirstTrainTimer timer, String departAt) {
        return service(now, timer, mock(RoomAccess.class)).search(UUID.randomUUID(), new RouteSearchRequest(31L, 33L, departAt, null));
    }

    @Test
    void runningHoursAnswerAsWithoutTheTimetable() {
        for (String departAt : List.of("2026-11-12T14:00:00+09:00", "2026-11-12T23:30:00+09:00")) {
            RouteSearchResponse before = search(NOW, null, departAt);
            RouteSearchResponse after = search(NOW, FirstTrainTimerTest.TIMER, departAt);
            assertThat(after).as(departAt).isEqualTo(before);
            var summary = after.routes().get(0).summary();
            assertThat(summary.noService()).isFalse();
            assertThat(summary.serviceNotice()).isNull();
            assertThat(summary.departureAt()).isEqualTo(departAt);
            assertThat(after.appointmentPassed()).isFalse();
            assertThat(after.notice()).isNull();
        }
        // Without departAt the request time is used, unchanged at 14:00.
        Instant afternoon = seoul("2026-11-12T14:00:00");
        assertThat(search(afternoon, FirstTrainTimerTest.TIMER, null)).isEqualTo(search(afternoon, null, null));
    }

    @Test
    void noServiceMovesDepartureAndArrivalButNotTheRoute() {
        RouteSearchResponse before = search(NOW, null, "2026-11-12T03:00:00+09:00");
        RouteSearchResponse after = search(NOW, FirstTrainTimerTest.TIMER, "2026-11-12T03:00:00+09:00");
        var old = before.routes().get(0).summary();
        var moved = after.routes().get(0).summary();
        assertThat(old.arrivalAt()).isEqualTo("2026-11-12T03:05:00+09:00");
        assertThat(moved.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(moved.arrivalAt()).isEqualTo("2026-11-12T05:41:00+09:00");
        assertThat(moved.noService()).isTrue();
        assertThat(moved.serviceNotice()).isEqualTo("요청한 시각에는 이 경로를 운행하는 열차가 없어 첫차(11월 12일 05:36 출발) 기준으로 안내합니다.");
        // Travel time, fare, transfers and steps stay.
        assertThat(moved.totalTimeSec()).isEqualTo(old.totalTimeSec());
        assertThat(moved.fare()).isEqualTo(old.fare());
        assertThat(moved.transferCount()).isEqualTo(old.transferCount());
        assertThat(after.routes().get(0).steps()).extracting(RouteSearchResponse.Step::stops)
                .isEqualTo(before.routes().get(0).steps().stream().map(RouteSearchResponse.Step::stops).toList());
    }

    @Test
    void aPassedAppointmentIsReported() {
        UUID roomId = UUID.randomUUID();
        MeetingRoom room = new MeetingRoom("ABC123", "방", UUID.randomUUID());
        Instant now = seoul("2026-11-12T14:00:00");
        room.schedule(now.minusSeconds(3600));
        RoomAccess rooms = mock(RoomAccess.class);
        when(rooms.requireMember(any(), any())).thenReturn(room);

        RouteSearchResponse response = service(now, FirstTrainTimerTest.TIMER, rooms).search(UUID.randomUUID(),
                new RouteSearchRequest(31L, 33L, "2026-11-12T20:00:00+09:00", roomId.toString()));

        assertThat(response.appointmentPassed()).isTrue();
        assertThat(response.notice()).isEqualTo(RouteSearchService.APPOINTMENT_PASSED_NOTICE);
        // departAt is ignored: leaving now.
        assertThat(response.routes().get(0).summary().departureAt()).isEqualTo("2026-11-12T14:00:00+09:00");
    }

    // ---- arrivesAfterAppointment ----

    /** 강남 -> 서초 of FirstTrainTimerTest, counted as a 40-minute route. */
    private static RouteDraft fortyMinutes() {
        RouteDraft base = FirstTrainTimerTest.gangnamToSeocho();
        return new RouteDraft(base.steps(), 2400, base.fare(), base.fareSource(), base.transferCount(), base.fromStationId(),
                base.toStationId());
    }

    private static RouteSearchResponse.Summary withAppointment(Instant now, Instant appointment, String departAt) {
        MeetingRoom room = new MeetingRoom("ABC123", "방", UUID.randomUUID());
        room.schedule(appointment);
        RoomAccess rooms = mock(RoomAccess.class);
        when(rooms.requireMember(any(), any())).thenReturn(room);
        return service(now, FirstTrainTimerTest.TIMER, rooms, fortyMinutes()).search(UUID.randomUUID(),
                new RouteSearchRequest(31L, 33L, departAt, UUID.randomUUID().toString())).routes().get(0).summary();
    }

    @Test
    void aDawnAppointmentPushedToTheFirstTrainArrivesAfterIt() {
        // Appointment 06:00, 40 minutes: leave 05:20, before the 05:36 first train -> arrive 06:16.
        var summary = withAppointment(seoul("2026-11-12T02:00:00"), seoul("2026-11-12T06:00:00"), null);
        assertThat(summary.noService()).isTrue();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-12T06:16:00+09:00");
        assertThat(summary.arrivesAfterAppointment()).isTrue();
    }

    @Test
    void enoughTimeArrivesExactlyAtTheAppointment() {
        // Tomorrow 14:00: leave 13:20 (a train runs), arrive at 14:00 itself -> not after it.
        var summary = withAppointment(seoul("2026-11-12T14:00:00"), seoul("2026-11-13T14:00:00"), null);
        assertThat(summary.noService()).isFalse();
        assertThat(summary.departureAt()).isEqualTo("2026-11-13T13:20:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-13T14:00:00+09:00");
        assertThat(summary.arrivesAfterAppointment()).isFalse();
    }

    @Test
    void aPlannedDepartureAlreadyPastLeavesNowAndArrivesAfterTheAppointment() {
        // Appointment 14:10 has not passed, but 14:10 − 40 min = 13:30 has: leave now (14:00), arrive 14:40.
        var summary = withAppointment(seoul("2026-11-12T14:00:00"), seoul("2026-11-12T14:10:00"), null);
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T14:00:00+09:00");
        assertThat(summary.arrivesAfterAppointment()).isTrue();
    }

    @Test
    void aPassedAppointmentIsNotComparedWithArrivals() {
        MeetingRoom room = new MeetingRoom("ABC123", "방", UUID.randomUUID());
        room.schedule(seoul("2026-11-12T13:00:00"));
        RoomAccess rooms = mock(RoomAccess.class);
        when(rooms.requireMember(any(), any())).thenReturn(room);
        RouteSearchResponse response = service(seoul("2026-11-12T14:00:00"), FirstTrainTimerTest.TIMER, rooms, fortyMinutes())
                .search(UUID.randomUUID(), new RouteSearchRequest(31L, 33L, null, UUID.randomUUID().toString()));
        assertThat(response.appointmentPassed()).isTrue();
        assertThat(response.routes().get(0).summary().arrivesAfterAppointment()).isFalse();
    }

    @Test
    void withoutARoomArrivalsAreNeverAfterAnAppointment() {
        RouteSearchResponse response = service(seoul("2026-11-12T14:00:00"), FirstTrainTimerTest.TIMER, mock(RoomAccess.class),
                fortyMinutes()).search(UUID.randomUUID(), new RouteSearchRequest(31L, 33L, "2026-11-12T03:00:00+09:00", null));
        assertThat(response.routes().get(0).summary().noService()).isTrue();
        assertThat(response.routes().get(0).summary().arrivesAfterAppointment()).isFalse();
    }

    @Test
    void arrivesAfterComparesWholeSeconds() {
        Instant appointment = Instant.parse("2026-11-12T05:00:00Z");
        assertThat(RouteSearchService.arrivesAfter(appointment, appointment)).isFalse();
        assertThat(RouteSearchService.arrivesAfter(appointment.plusMillis(900), appointment)).isFalse();
        assertThat(RouteSearchService.arrivesAfter(appointment.plusSeconds(1), appointment)).isTrue();
        assertThat(RouteSearchService.arrivesAfter(appointment.plusSeconds(1), null)).isFalse();
    }
}
