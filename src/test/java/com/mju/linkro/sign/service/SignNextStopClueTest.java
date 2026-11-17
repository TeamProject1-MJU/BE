package com.mju.linkro.sign.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.sign.service.SignRouteLookup.RideEdge;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Signs without 상행/하행/내선/외선 text (#158): the station number, and the stations the arrow points to located on the
 * line graph, decide. The graph is the real V11 seed (stations, station_lines, RIDE edges).
 */
class SignNextStopClueTest {
    static final long LINE2 = 2;
    static final Map<String, Long> STATION_IDS = new LinkedHashMap<>();
    static final Map<String, Set<Long>> LINES_OF = new LinkedHashMap<>();
    static final List<RideEdge> SEED_EDGES = new ArrayList<>();

    AiClient ai = mock(AiClient.class);
    StationLookup stations = mock(StationLookup.class);
    LineLookup lines = mock(LineLookup.class);
    SignRouteLookup routes = mock(SignRouteLookup.class);
    SignCheckService service = new SignCheckService(ai, stations, lines, routes);

    @BeforeAll
    static void loadSeed() throws IOException {
        String sql;
        try (InputStream in = SignNextStopClueTest.class.getResourceAsStream("/db/migration/V11__seed_subway_data.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, String> codeToName = new LinkedHashMap<>();
        Matcher node = Pattern.compile("\\('([^']+)', '(\\d+)', '(\\d+)', \\d+, '[A-Z_]+'\\)").matcher(sql);
        while (node.find()) {
            STATION_IDS.putIfAbsent(node.group(1), (long) STATION_IDS.size() + 1);
            LINES_OF.computeIfAbsent(node.group(1), k -> new HashSet<>()).add(Long.parseLong(node.group(2)));
            codeToName.put(node.group(2) + ":" + node.group(3), node.group(1));
        }
        Matcher ride = Pattern.compile("\\('(\\d+)', '(\\d+)', '(\\d+)', '(\\d+)', 'RIDE', '([A-Z]+)', \\d+, \\d+\\)").matcher(sql);
        while (ride.find()) {
            String from = codeToName.get(ride.group(1) + ":" + ride.group(2));
            String to = codeToName.get(ride.group(3) + ":" + ride.group(4));
            SEED_EDGES.add(new RideEdge(Long.parseLong(ride.group(1)), STATION_IDS.get(from), STATION_IDS.get(to), to,
                    DirectionCode.valueOf(ride.group(5))));
        }
        assertThat(SEED_EDGES).hasSizeGreaterThan(500);
    }

    static long id(String name) {
        return STATION_IDS.get(name);
    }

    @BeforeEach
    void setUp() {
        when(lines.name(LINE2)).thenReturn(Optional.of("2호선"));
        STATION_IDS.forEach((name, id) -> when(stations.require(id)).thenReturn(new StationInfo(id, name, 0, 0, true)));
        when(routes.rideEdges(anyLong())).thenAnswer(call -> {
            long station = call.getArgument(0);
            Set<Long> served = new HashSet<>();
            STATION_IDS.forEach((name, id) -> { if (id == station) served.addAll(LINES_OF.get(name)); });
            return SEED_EDGES.stream().filter(edge -> served.contains(edge.lineId())).toList();
        });
        when(routes.codeMatches(id("을지로입구"), LINE2, "202")).thenReturn(true);
    }

    SignCheckResponse check(String station, String direction, SignReading reading) {
        when(ai.readSign(any(), any())).thenReturn(reading);
        return service.check(SignTestImages.png(), new SignRouteRequest(id(station), LINE2, direction, null));
    }

    static SignReading read(String station, String line, String code, String... toward) {
        return new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO, station, line, null, code, Arrays.asList(toward));
    }

    static SignReason reasonOf(SignResult result) {
        return result == SignResult.CORRECT ? null : result == SignResult.WRONG ? SignReason.DIRECTION_MISMATCH : SignReason.MISSING_INFO;
    }

    @ParameterizedTest
    @CsvSource({"OUTER,시청,CORRECT", "OUTER,을지로3가,WRONG", "INNER,을지로3가,CORRECT", "INNER,시청,WRONG",
            "OUTER,시청역,CORRECT", "INNER, 을지로3가 ,CORRECT"})
    void nextStopWithArrowDecidesInnerOrOuterWithoutDirectionText(String direction, String next, SignResult result) {
        // The device case: station name read, no line, no direction text; the AI itself answered RETAKE.
        SignCheckResponse response = check("을지로입구", direction, read("을지로입구역", null, null, next));
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.reason()).isEqualTo(reasonOf(result));
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("을지로입구역", "2호선", next.strip()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INNER|선릉,교대,사당,신도림|CORRECT", "OUTER|선릉,교대,사당,신도림|WRONG",
            "OUTER|성수,건대입구|CORRECT", "INNER|성수,건대입구|WRONG",
            "INNER|선릉,성수|RETAKE", "INNER|없는역,선릉|CORRECT", "INNER|없는역|RETAKE"})
    void majorStationsAlongTheArrowDecideOnTheLoop(String direction, String toward, SignResult result) {
        // 잠실 concourse: "선릉 · 교대 · 사당  신도림  (2) →". None of them is the next stop (잠실새내).
        SignCheckResponse response = check("잠실", direction, read("잠실", "2호선", null, toward.split(",")));
        assertThat(response.result()).isEqualTo(result);
        assertThat(response.reason()).isEqualTo(reasonOf(result));
        if (result != SignResult.RETAKE) {
            assertThat(response.recognized().headsign()).isEqualTo(String.join(" · ", toward.split(",")));
        }
    }

    @Test
    void branchStationsUseTheBranchDirection() {
        // 성수지선: 성수 -> 용답 -> 신답 -> 용두 -> 신설동 is DOWN in V11.
        assertThat(check("성수", "DOWN", read("성수", "2호선", null, "용답", "신설동")).result()).isEqualTo(SignResult.CORRECT);
        assertThat(check("성수", "OUTER", read("성수", "2호선", null, "용답", "신설동")).reason()).isEqualTo(SignReason.MISSING_INFO);
        assertThat(check("용답", "UP", read("용답", "2호선", null, "성수")).result()).isEqualTo(SignResult.CORRECT);
        assertThat(check("용답", "DOWN", read("용답", "2호선", null, "성수")).result()).isEqualTo(SignResult.WRONG);
    }

    @Test
    void stationNumberAloneStandsInForStationAndLine() {
        SignCheckResponse response = check("을지로입구", "OUTER", read(null, null, "202", "시청"));
        assertThat(response.result()).isEqualTo(SignResult.CORRECT);
        assertThat(response.recognized()).isEqualTo(new SignCheckResponse.Place("을지로입구", "2호선", "시청"));
    }

    @Test
    void stationNumberFillsTheLineForAnExplicitDirectionMark() {
        SignReading reading = new SignReading(SignResult.CORRECT, null, "을지로입구", null, "외선순환", "202", List.of());
        SignCheckResponse response = check("을지로입구", "OUTER", reading);
        assertThat(response.result()).isEqualTo(SignResult.CORRECT);
        assertThat(response.recognized().lineName()).isEqualTo("2호선");
        // Without the number the line is still missing.
        reading = new SignReading(SignResult.CORRECT, null, "을지로입구", null, "외선순환", "203", List.of());
        assertThat(check("을지로입구", "OUTER", reading).reason()).isEqualTo(SignReason.MISSING_INFO);
    }

    @Test
    void unusableCluesStayMissingInfo() {
        for (SignReading reading : List.of(
                read("을지로입구", null, null),                   // nothing on the arrow side
                read("시청", null, null, "을지로3가"),            // another station's sign
                read(null, null, "201", "을지로3가"),             // another station's number
                read("을지로입구", "3호선", null, "을지로3가"))) { // another line
            SignCheckResponse response = check("을지로입구", "OUTER", reading);
            assertThat(response.result()).as(reading.toString()).isEqualTo(SignResult.RETAKE);
            assertThat(response.reason()).isEqualTo(SignReason.MISSING_INFO);
        }
    }

    @Test
    void handMadeGraphTieAndOtherLineAreAmbiguous() {
        // A 4-station loop: from A, C is 2 stops either way. Station A is also on line 1, which reaches B.
        long a = 1, b = 2, c = 3, d = 4;
        when(stations.require(a)).thenReturn(new StationInfo(a, "A", 0, 0, true));
        when(routes.rideEdges(a)).thenReturn(List.of(
                new RideEdge(2, a, b, "B", DirectionCode.INNER), new RideEdge(2, b, c, "C", DirectionCode.INNER),
                new RideEdge(2, c, d, "D", DirectionCode.INNER), new RideEdge(2, d, a, "A", DirectionCode.INNER),
                new RideEdge(2, a, d, "D", DirectionCode.OUTER), new RideEdge(2, d, c, "C", DirectionCode.OUTER),
                new RideEdge(2, c, b, "B", DirectionCode.OUTER), new RideEdge(2, b, a, "A", DirectionCode.OUTER),
                new RideEdge(1, a, b, "B", DirectionCode.UP)));
        when(ai.readSign(any(), any())).thenReturn(read("A", "2호선", null, "C"));
        assertThat(service.check(SignTestImages.png(), new SignRouteRequest(a, LINE2, "INNER", null)).reason())
                .isEqualTo(SignReason.MISSING_INFO);
        when(ai.readSign(any(), any())).thenReturn(read("A", "2호선", null, "C", "B"));
        assertThat(service.check(SignTestImages.png(), new SignRouteRequest(a, LINE2, "INNER", null)).reason())
                .isEqualTo(SignReason.MISSING_INFO);
        when(ai.readSign(any(), any())).thenReturn(read("A", "2호선", null, "B"));
        assertThat(service.check(SignTestImages.png(), new SignRouteRequest(a, LINE2, "INNER", null)).result())
                .isEqualTo(SignResult.CORRECT);
        when(ai.readSign(any(), any())).thenReturn(read("A", null, null, "B")); // B is reached on line 1 too
        assertThat(service.check(SignTestImages.png(), new SignRouteRequest(a, LINE2, "INNER", null)).reason())
                .isEqualTo(SignReason.MISSING_INFO);
        when(ai.readSign(any(), any())).thenReturn(read("A", null, null, "D"));
        assertThat(service.check(SignTestImages.png(), new SignRouteRequest(a, LINE2, "INNER", null)).result())
                .isEqualTo(SignResult.WRONG);
    }

    @Test
    void photoFailuresAndOtherLineAreNotOverridden() {
        for (SignReason reason : new SignReason[]{SignReason.NOT_A_SIGN, SignReason.UNREADABLE, SignReason.LINE_NOT_ON_ROUTE}) {
            SignReading reading = new SignReading(reason.result(), reason, "을지로입구", null, null, "202", List.of("시청"));
            assertThat(check("을지로입구", "OUTER", reading).reason()).isEqualTo(reason);
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
