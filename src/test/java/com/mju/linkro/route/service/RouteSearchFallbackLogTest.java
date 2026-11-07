package com.mju.linkro.route.service;

import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.kakao.KakaoRouteClient;
import com.mju.linkro.route.kakao.KakaoRouteClient.Failed;
import com.mju.linkro.route.kakao.KakaoRouteClient.Reason;
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
}
