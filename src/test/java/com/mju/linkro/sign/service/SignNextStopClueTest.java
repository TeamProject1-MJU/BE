package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.sign.service.SignRouteLookup.NextStop;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Signs without 상행/하행/내선/외선 text: next stop + our graph, and the station number, decide (#158). */
class SignNextStopClueTest {
    static final long EULJIRO1 = 202, LINE2 = 2, LINE1 = 1;

    AiClient ai = mock(AiClient.class);
    StationLookup stations = mock(StationLookup.class);
    LineLookup lines = mock(LineLookup.class);
    SignRouteLookup routes = mock(SignRouteLookup.class);
    SignCheckService service = new SignCheckService(ai, stations, lines, routes);

    @BeforeEach
    void setUp() {
        when(stations.require(EULJIRO1)).thenReturn(new StationInfo(EULJIRO1, "을지로입구", 37.56, 126.98, true));
        when(lines.name(LINE2)).thenReturn(Optional.of("2호선"));
        // Same as V11: line 2 INNER runs 시청 -> 을지로입구 -> 을지로3가.
        when(routes.nextStops(EULJIRO1)).thenReturn(List.of(
                new NextStop(LINE2, "을지로3가", DirectionCode.INNER), new NextStop(LINE2, "시청", DirectionCode.OUTER)));
        when(routes.codeMatches(EULJIRO1, LINE2, "202")).thenReturn(true);
    }

    SignCheckResponse check(String direction, SignReading reading) {
        when(ai.readSign(any(), any())).thenReturn(reading);
        return service.check(SignTestImages.png(), new SignRouteRequest(EULJIRO1, LINE2, direction, null));
    }

    static SignReading read(String station, String line, String code, String next) {
        return new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO, station, line, null, code, next);
    }

    @ParameterizedTest
    @CsvSource({"OUTER,시청,CORRECT", "OUTER,을지로3가,WRONG", "INNER,을지로3가,CORRECT", "INNER,시청,WRONG",
            "OUTER,시청역,CORRECT", "INNER, 을지로3가 ,CORRECT"})
    void nextStopWithArrowDecidesInnerOrOuterWithoutDirectionText(String direction, String next, SignResult result) {
        // The device case: station name read, no line, no direction text; the AI itself answered RETAKE.
        SignCheckResponse response = check(direction, read("을지로입구역", null, null, next));
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.reason()).isEqualTo(result == SignResult.CORRECT ? null : SignReason.DIRECTION_MISMATCH);
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("을지로입구역", "2호선", next));
    }

    @Test
    void stationNumberAloneStandsInForStationAndLine() {
        SignCheckResponse response = check("OUTER", read(null, null, "202", "시청"));
        assertThat(response.result()).isEqualTo(SignResult.CORRECT);
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("을지로입구", "2호선", "시청"));
    }

    @Test
    void stationNumberFillsTheLineForAnExplicitDirectionMark() {
        SignReading reading = new SignReading(SignResult.CORRECT, null, "을지로입구", null, "외선순환", "202", null);
        SignCheckResponse response = check("OUTER", reading);
        assertThat(response.result()).isEqualTo(SignResult.CORRECT);
        assertThat(response.recognized().lineName()).isEqualTo("2호선");
        // Without the number the line is still missing.
        reading = new SignReading(SignResult.CORRECT, null, "을지로입구", null, "외선순환", "203", null);
        assertThat(check("OUTER", reading).reason()).isEqualTo(SignReason.MISSING_INFO);
    }

    @Test
    void unusableCluesStayMissingInfo() {
        for (SignReading reading : List.of(
                read("을지로입구", null, null, null),            // no next stop
                read("을지로입구", null, null, "왕십리"),         // a terminal, not a next stop
                read("시청", null, null, "을지로3가"),            // another station's sign
                read(null, null, "201", "을지로3가"),             // another station's number
                read("을지로입구", "3호선", null, "을지로3가"))) { // another line
            SignCheckResponse response = check("OUTER", reading);
            assertThat(response.result()).as(reading.toString()).isEqualTo(SignResult.RETAKE);
            assertThat(response.reason()).isEqualTo(SignReason.MISSING_INFO);
        }
    }

    @Test
    void sameNextStopNameOnAnotherLineNeedsTheLine() {
        when(routes.nextStops(EULJIRO1)).thenReturn(List.of(new NextStop(LINE2, "시청", DirectionCode.OUTER),
                new NextStop(LINE1, "시청", DirectionCode.DOWN)));
        assertThat(check("OUTER", read("을지로입구", null, null, "시청")).reason()).isEqualTo(SignReason.MISSING_INFO);
        assertThat(check("OUTER", read("을지로입구", "2호선", null, "시청")).result()).isEqualTo(SignResult.CORRECT);
        assertThat(check("OUTER", read(null, null, "202", "시청")).result()).isEqualTo(SignResult.CORRECT);
    }

    @Test
    void photoFailuresAndOtherLineAreNotOverridden() {
        for (SignReason reason : new SignReason[]{SignReason.NOT_A_SIGN, SignReason.UNREADABLE, SignReason.LINE_NOT_ON_ROUTE}) {
            SignReading reading = new SignReading(reason.result(), reason, "을지로입구", null, null, "202", "시청");
            assertThat(check("OUTER", reading).reason()).isEqualTo(reason);
        }
    }

    @Test
    void namesCompareWithoutSuffixSpacesOrParentheses() {
        assertThat(SignCheckService.sameStation("을지로입구역", "을지로입구")).isTrue();
        assertThat(SignCheckService.sameStation("을지로입구 (IBK기업은행)", "을지로입구")).isTrue();
        assertThat(SignCheckService.sameStation("서울역", "서울역")).isTrue();
        assertThat(SignCheckService.sameStation("시청", "을지로입구")).isFalse();
        assertThat(SignCheckService.sameLine("2 호선", "2호선")).isTrue();
        assertThat(SignCheckService.sameLine("2", "2호선")).isTrue();
        assertThat(SignCheckService.sameLine("3호선", "2호선")).isFalse();
    }
}
