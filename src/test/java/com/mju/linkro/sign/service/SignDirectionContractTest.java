package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.*;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.subway.api.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SignDirectionContractTest {
    AiClient ai = mock(AiClient.class);
    StationLookup stations = mock(StationLookup.class);
    LineLookup lines = mock(LineLookup.class);
    SignRouteLookup routes = mock(SignRouteLookup.class);
    SignCheckService service = new SignCheckService(ai, stations, lines, routes);

    @ParameterizedTest
    @CsvSource({"UP,상행,CORRECT", "UP,하행,WRONG", "DOWN,하행,CORRECT", "INNER,내선순환,CORRECT",
            "OUTER,외선순환,CORRECT", "INNER,외선순환,WRONG", "OUTER,내선순환,WRONG",
            "LOOP,응암순환,CORRECT", "LOOP,하행,RETAKE", "UP,신설동 방면,RETAKE",
            "INNER,성수 방면,RETAKE", "DOWN,종착역,RETAKE", "UP,상행/하행,RETAKE"})
    void directionRequiresExplicitEvidence(String direction, String observed, SignResult result) {
        when(stations.require(105)).thenReturn(new StationInfo(105, "역", 0, 0, true));
        when(lines.name(2)).thenReturn(Optional.of("노선"));
        // Even an overconfident AI cannot promote a terminal name to a direction match.
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, "역", "노선", observed));
        var response = service.check(SignTestImages.png(), new SignRouteRequest(105L, 2L, direction, null));
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.reason()).isEqualTo(result == SignResult.CORRECT ? null
                : result == SignResult.WRONG ? SignReason.DIRECTION_MISMATCH : SignReason.MISSING_INFO);
        assertThat(response.expected().headsign()).isNull();
        verify(routes).requireDirection(105, 2, DirectionCode.valueOf(direction));
        verify(ai).readSign(any(), eq(new SignContext("역", "노선", DirectionCode.valueOf(direction), null)));
    }

    @Test
    void otherLineAndPhotoFailuresRemainDistinct() {
        when(stations.require(105)).thenReturn(new StationInfo(105, "역", 0, 0, true));
        when(lines.name(2)).thenReturn(Optional.of("노선"));
        for (SignReason reason : new SignReason[]{SignReason.LINE_NOT_ON_ROUTE, SignReason.NOT_A_SIGN,
                SignReason.UNREADABLE, SignReason.MISSING_INFO}) {
            when(ai.readSign(any(), any())).thenReturn(new SignReading(reason.result(), reason, null, "다른 노선", null));
            var response = service.check(SignTestImages.png(), new SignRouteRequest(105L, 2L, "INNER", null));
            assertThat(response.result()).isEqualTo(reason.result());
            assertThat(response.reason()).isEqualTo(reason);
        }
    }

    @Test
    void invalidRouteNeverReachesPhotoAnalysis() {
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR)).when(routes).requireDirection(105, 2, DirectionCode.UP);
        assertThatThrownBy(() -> service.check(SignTestImages.png(), new SignRouteRequest(105L, 2L, "UP", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(ai, stations, lines);
    }

    @Test
    void inputAlternativesAndBlankNormalization() {
        for (DirectionCode direction : DirectionCode.values()) {
            for (String headsign : new String[]{null, "", " "}) {
                assertThat(new SignRouteRequest(1L, 2L, direction.name(), headsign).validate().headsign()).isNull();
            }
        }
        assertThat(new SignRouteRequest(1L, 2L, null, " 방면 ").validate().headsign()).isEqualTo("방면");
        for (SignRouteRequest bad : new SignRouteRequest[]{
                new SignRouteRequest(1L, 2L, null, null), new SignRouteRequest(1L, 2L, " ", " "),
                new SignRouteRequest(1L, 2L, "up", null), new SignRouteRequest(1L, 2L, "SIDEWAYS", "방면"),
                new SignRouteRequest(null, 2L, "UP", null), new SignRouteRequest(1L, null, "UP", null)}) {
            assertThatThrownBy(bad::validate).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        }
    }
}
