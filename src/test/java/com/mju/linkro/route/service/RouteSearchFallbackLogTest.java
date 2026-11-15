package com.mju.linkro.route.service;

import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.route.kakao.KakaoRouteClient.Failed;
import com.mju.linkro.route.kakao.KakaoRouteClient.Reason;
import com.mju.linkro.route.kakao.KakaoRouteResponse;
import com.mju.linkro.subway.api.CongestionLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.*;

/** Every Kakao failure falls back to INTERNAL at once; expected states log INFO, failures WARN. */
@ExtendWith(OutputCaptureExtension.class)
class RouteSearchFallbackLogTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"NO_KEY, INFO", "COOLDOWN, INFO", "BUSY, WARN", "TIMEOUT, WARN", "IO_ERROR, WARN", "SERVER_ERROR, WARN",
            "AUTH_ERROR, WARN", "QUOTA, WARN", "CLIENT_ERROR, WARN", "BAD_RESPONSE, WARN"})
    void failuresFallBackToInternal(Reason reason, String level, CapturedOutput output) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(35)).thenReturn(new StationInfo(35, "사당", 37.47, 126.98, true));
        KakaoRouteClient kakao = mock(KakaoRouteClient.class);
        when(kakao.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(new Failed(reason));
        KakaoRouteMapper mapper = mock(KakaoRouteMapper.class);
        InternalRouteBuilder internal = mock(InternalRouteBuilder.class);
        when(internal.build(31, 35)).thenReturn(new RouteDraft(List.of(), 600, 1450, FareSource.ESTIMATED, 0, 31, 35));
        var service = new RouteSearchService(mock(RoomAccess.class), stations, kakao, mapper, internal,
                mock(CongestionLookup.class), Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));

        RouteSearchResponse response = service.search(UUID.randomUUID(), new RouteSearchRequest(31L, 35L, null, null));

        assertThat(response.routeSource()).isEqualTo(RouteSearchResponse.RouteSource.INTERNAL);
        verify(kakao, times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verifyNoInteractions(mapper);
        assertThat(output.getAll().lines().filter(line -> line.contains("Route search 31 -> 35 uses INTERNAL: " + reason)))
                .singleElement().satisfies(line -> assertThat(line).contains(" " + level + " "));
    }

    /**
     * #142: a Kakao body whose every candidate is broken (null route, null step, null path point, a list failing when
     * read) is not a 500: no usable subway route, so INTERNAL, with Kakao called once.
     */
    @org.junit.jupiter.api.Test
    void brokenKakaoCandidatesFallBackToInternalInsteadOfFailing(CapturedOutput output) {
        StationLookup stations = mock(StationLookup.class);
        when(stations.requireSupported(31)).thenReturn(new StationInfo(31, "강남", 37.49, 127.02, true));
        when(stations.requireSupported(33)).thenReturn(new StationInfo(33, "서초", 37.49, 127.00, true));
        RouteReference reference = mock(RouteReference.class);
        when(reference.index()).thenReturn(RouteReference.Index.of(FirstTrainTimerTest.DATA));
        KakaoRouteMapper mapper = new KakaoRouteMapper(reference, com.mju.linkro.subway.mock.MockFareCalculatorAccess.calculator());
        List<KakaoRouteResponse.Stop> failing = new java.util.AbstractList<>() {
            @Override public KakaoRouteResponse.Stop get(int index) { throw new IllegalStateException("broken stop list"); }
            @Override public int size() { return 3; }
        };
        KakaoRouteResponse body = new KakaoRouteResponse("OK", java.util.Arrays.asList(
                null,
                KakaoRouteMapperTest.withSteps((KakaoRouteResponse.Step) null),
                KakaoRouteMapperTest.withSteps(new KakaoRouteResponse.Step(new KakaoRouteResponse.StepProperties("WALKING", "walk", 80, 60,
                        List.of(), List.of()), new KakaoRouteResponse.Path(java.util.Arrays.asList((List<Double>) null)))),
                KakaoRouteMapperTest.withSteps(KakaoRouteMapperTest.subwayWithStops(failing, List.of(new KakaoRouteResponse.Vehicle("2호선"))))));
        KakaoRouteClient kakao = mock(KakaoRouteClient.class);
        when(kakao.search(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(new KakaoRouteClient.Found(body));
        InternalRouteBuilder internal = mock(InternalRouteBuilder.class);
        when(internal.build(31, 33)).thenReturn(new RouteDraft(List.of(), 600, 1450, FareSource.ESTIMATED, 0, 31, 33));
        var service = new RouteSearchService(mock(RoomAccess.class), stations, kakao, mapper, internal,
                mock(CongestionLookup.class), Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC));

        RouteSearchResponse response = service.search(UUID.randomUUID(), new RouteSearchRequest(31L, 33L, null, null));

        assertThat(response.routeSource()).isEqualTo(RouteSearchResponse.RouteSource.INTERNAL);
        verify(kakao, times(1)).search(anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(internal).build(31, 33);
        assertThat(output.getAll()).contains("Route search 31 -> 33 uses INTERNAL: no usable subway route")
                .contains("Kakao route candidate 4 skipped: IllegalStateException: broken stop list");
    }
}
