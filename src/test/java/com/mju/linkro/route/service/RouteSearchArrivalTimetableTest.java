package com.mju.linkro.route.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.RouteSource;
import com.mju.linkro.subway.api.TimedRoute;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.mju.linkro.route.service.FirstTrainTimerTest.seoul;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.ROUTER;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.seoulOffset;
import static com.mju.linkro.route.service.RouteSearchRoomTimetableTest.stationOrder;
import static com.mju.linkro.route.service.RouteSearchSoloTimetableTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/**
 * Arrival mode (arriveAt, #130) on the hand-made timetable of {@link FirstTrainTimerTest}: weekday trains leave 강남(31)
 * at 05:36 (W1, at 서초(33) 05:40) and 14:03 (W2, at 서초 14:07). Now is Thursday 2026-11-12 02:00.
 */
@ExtendWith(OutputCaptureExtension.class)
class RouteSearchArrivalTimetableTest {
    static final Instant NOW = seoul("2026-11-12T02:00:00");

    static RouteSearchResponse search(RouteSearchSoloTimetableTest.Fixture f, String departAt, boolean withRoom, String arriveAt) {
        return f.service().search(UUID.randomUUID(),
                new RouteSearchRequest(31L, 33L, departAt, withRoom ? UUID.randomUUID().toString() : null, arriveAt));
    }

    static Map<String, String> validationErrors(RouteSearchSoloTimetableTest.Fixture f, String departAt, String arriveAt) {
        BusinessException error = catchThrowableOfType(BusinessException.class, () -> search(f, departAt, false, arriveAt));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        @SuppressWarnings("unchecked")
        Map<String, String> details = (Map<String, String>) error.getDetails();
        return details;
    }

    @Test
    void theLatestRouteArrivingByArriveAtWithSlackAndWithoutKakao() {
        Instant arriveAt = seoul("2026-11-12T14:10:00");
        // What room appointment mode (and recommendations with an appointment) compute for this time.
        TimedRoute expected = ROUTER.latestDepartures(33, arriveAt, Set.of(31L), 0).get(31L);
        var f = fixture(NOW, null, ROUTER);

        RouteSearchResponse response = search(f, null, false, "2026-11-12T14:10:00+09:00");

        verifyNoInteractions(f.kakao());
        assertThat(response.routeSource()).isEqualTo(RouteSource.INTERNAL);
        assertThat(response.appointmentPassed()).isFalse();
        assertThat(response.notice()).isNull();
        assertThat(response.routes()).hasSize(1);
        var route = response.routes().get(0);
        assertThat(route.routeType()).isEqualTo("FASTEST");
        var summary = route.summary();
        assertThat(summary.departureAt()).isEqualTo(seoulOffset(expected.leaveAt())).isEqualTo("2026-11-12T14:03:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo(seoulOffset(expected.arriveAt())).isEqualTo("2026-11-12T14:07:00+09:00");
        assertThat(summary.totalTimeSec()).isEqualTo(240);
        assertThat(summary.slackSeconds()).isEqualTo(180);
        assertThat(summary.fareSource()).isEqualTo(FareSource.ESTIMATED);
        assertThat(summary.noService()).isFalse();
        assertThat(summary.serviceNotice()).isNull();
        assertThat(summary.arrivesAfterAppointment()).isFalse();
        // Backwards from the arrival there is no wait before leaving.
        assertThat(summary.waitSec()).isNull();
        assertThat(summary.waitKind()).isNull();
        assertThat(summary.totalWithWaitSec()).isNull();
        assertThat(stationOrder(route)).isEqualTo(stationOrder(expected.path().stops()));
    }

    @Test
    void arrivingExactlyAtArriveAtHasNoSlack() {
        var summary = search(fixture(NOW, null, ROUTER), null, false, "2026-11-12T14:07:00+09:00").routes().get(0).summary();
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-12T14:07:00+09:00");
        assertThat(summary.slackSeconds()).isZero();
    }

    @Test
    void arriveAtWinsOverTheRoomsAppointment() {
        // The room's appointment is 14:07 (W2), but arriveAt 05:45 asks for W1; the room is only checked for membership.
        var f = fixture(NOW, seoul("2026-11-12T14:07:00"), ROUTER);
        RouteSearchResponse response = search(f, null, true, "2026-11-12T05:45:00+09:00");

        verifyNoInteractions(f.kakao());
        var summary = response.routes().get(0).summary();
        assertThat(summary.departureAt()).isEqualTo("2026-11-12T05:36:00+09:00");
        assertThat(summary.arrivalAt()).isEqualTo("2026-11-12T05:40:00+09:00");
        assertThat(summary.slackSeconds()).isEqualTo(300);
        // Without arriveAt the same room is room appointment mode: no slack, as before.
        var room = search(f, null, true, null).routes().get(0).summary();
        assertThat(room.arrivalAt()).isEqualTo("2026-11-12T14:07:00+09:00");
        assertThat(room.slackSeconds()).isNull();
    }

    @Test
    void departAtWithArriveAtIsAValidationErrorForBothFields() {
        var f = fixture(NOW, null, ROUTER);
        assertThat(validationErrors(f, "2026-11-12T14:00:00+09:00", "2026-11-12T14:10:00+09:00"))
                .containsKeys("departAt", "arriveAt");
        verifyNoInteractions(f.kakao());
    }

    @Test
    void arriveAtMustBeFromNowToTheConfiguredDays() {
        var f = fixture(NOW, null, ROUTER);
        assertThat(validationErrors(f, null, "2026-11-12T01:59:59+09:00")).containsEntry("arriveAt", "arriveAt은 지금 이후여야 합니다.");
        assertThat(validationErrors(f, null, "2026-12-12T02:00:01+09:00")).containsEntry("arriveAt", "arriveAt은 지금부터 30일 이내여야 합니다.");
        assertThat(validationErrors(f, null, "not-a-time")).containsKey("arriveAt");
        // Now itself (whole seconds) and exactly 30 days ahead are allowed: they reach the timetable checks instead.
        assertThat(catchThrowableOfType(BusinessException.class, () -> search(f, null, false, "2026-11-12T02:00:00+09:00")).getErrorCode())
                .isEqualTo(ErrorCode.NO_SERVICE_TIME);
        assertThat(catchThrowableOfType(BusinessException.class, () -> search(f, null, false, "2026-12-12T02:00:00+09:00")).getErrorCode())
                .isEqualTo(ErrorCode.NO_SERVICE_TIME);
    }

    @Test
    void noTrainOfAnyLineBeforeArriveAtIsNoServiceTime() {
        // 04:00 Thursday: no train arrives anywhere from 03:00 (the router's own check, as recommendations use it).
        assertThat(ROUTER.noTrainArrivesBefore(seoul("2026-11-12T04:00:00"))).isTrue();
        var f = fixture(NOW, null, ROUTER);
        BusinessException error = catchThrowableOfType(BusinessException.class, () -> search(f, null, false, "2026-11-12T04:00:00+09:00"));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NO_SERVICE_TIME);
        verifyNoInteractions(f.kakao());
    }

    @Test
    void trainsRunButNoneArrivesInTimeIsArrivalUnreachable() {
        // 05:39: W1 has left 강남 (05:36) so trains run, but it reaches 서초 at 05:40.
        assertThat(ROUTER.noTrainArrivesBefore(seoul("2026-11-12T05:39:00"))).isFalse();
        var f = fixture(NOW, null, ROUTER);
        BusinessException error = catchThrowableOfType(BusinessException.class, () -> search(f, null, false, "2026-11-12T05:39:00+09:00"));
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.ARRIVAL_UNREACHABLE);
        assertThat(error.getMessage()).isEqualTo("그 시각까지 도착할 수 있는 열차가 없습니다. 도착 시각을 늦춰 주세요.");
        verifyNoInteractions(f.kakao());
    }

    @Test
    void withoutTheRouterArriveAtIsIgnoredWithAWarning(CapturedOutput output) {
        var withArrival = fixture(NOW, null, null);
        RouteSearchResponse ignored = search(withArrival, null, false, "2026-11-12T14:10:00+09:00");

        assertThat(ignored).isEqualTo(search(fixture(NOW, null, null), null, false, null));
        verify(withArrival.kakao(), times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(ignored.routes().get(0).summary().slackSeconds()).isNull();
        assertThat(output.getAll()).contains(" WARN ").contains("Route search 31 -> 33 ignores arriveAt: no timetable router")
                .doesNotContain("127.0").doesNotContain("37.49");
    }

    @Test
    void theSameStationWithArriveAtIsAnsweredAsBefore() {
        var f = fixture(NOW, null, ROUTER);
        RouteSearchResponse response = f.service().search(UUID.randomUUID(), new RouteSearchRequest(31L, 31L, null, null, "2026-11-12T14:10:00+09:00"));
        assertThat(response).isEqualTo(fixture(NOW, null, null).service().search(UUID.randomUUID(), new RouteSearchRequest(31L, 31L, null, null)));
        verifyNoInteractions(f.kakao());
    }
}
