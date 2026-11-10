package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.kakao.KakaoRouteResponse;
import com.mju.linkro.route.kakao.KakaoRouteResponse.*;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.mock.MockFareCalculatorAccess;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hand-made 2호선 강남(1)-교대(2)-서초(3) INNER and 3호선 교대(4)-고속터미널(5) DOWN, transfer at 교대. */
class KakaoRouteMapperTest {
    private static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new SubwayGraphData.Line(2, "2", "2호선", "#00A84D", 2), new SubwayGraphData.Line(3, "3", "3호선", "#EF7C1C", 3)),
            List.of(new SubwayGraphData.Station(31, "강남", 37.49, 127.02, true), new SubwayGraphData.Station(32, "교대", 37.49, 127.01, true),
                    new SubwayGraphData.Station(33, "서초", 37.49, 127.00, true), new SubwayGraphData.Station(40, "고속터미널", 37.50, 127.00, true)),
            List.of(new SubwayGraphData.Node(1, 31, 2, "222", 1, "MAIN"), new SubwayGraphData.Node(2, 32, 2, "223", 2, "MAIN"),
                    new SubwayGraphData.Node(3, 33, 2, "224", 3, "MAIN"), new SubwayGraphData.Node(4, 32, 3, "340", 1, "MAIN"),
                    new SubwayGraphData.Node(5, 40, 3, "339", 2, "MAIN")),
            List.of(new SubwayGraphData.Edge(1, 1, 2, StepType.RIDE, DirectionCode.INNER, 120, 1200),
                    new SubwayGraphData.Edge(2, 2, 3, StepType.RIDE, DirectionCode.INNER, 120, 1300),
                    new SubwayGraphData.Edge(3, 2, 1, StepType.RIDE, DirectionCode.OUTER, 120, 1200),
                    new SubwayGraphData.Edge(4, 4, 5, StepType.RIDE, DirectionCode.DOWN, 100, 1000),
                    new SubwayGraphData.Edge(5, 2, 4, StepType.TRANSFER, null, 60, 80)));

    private final KakaoRouteMapper mapper = mapper();

    private static KakaoRouteMapper mapper() {
        RouteReference reference = mock(RouteReference.class);
        when(reference.index()).thenReturn(RouteReference.Index.of(DATA));
        return new KakaoRouteMapper(reference, MockFareCalculatorAccess.calculator());
    }

    static Step subway(String line, int time, String... stops) {
        return new Step(new StepProperties("SUBWAY", "Kakao text", 1000 * (stops.length - 1), time,
                java.util.Arrays.stream(stops).map(Stop::new).toList(), List.of(new Vehicle(line))),
                new Path(List.of(List.of(127.0, 37.5), List.of(127.1, 37.6))));
    }

    static Step step(String type, int time) {
        return new Step(new StepProperties(type, type + " text", 80, time, List.of(), List.of()), null);
    }

    static Route route(String type, Integer fare, Step... steps) {
        int total = java.util.Arrays.stream(steps).mapToInt(s -> s.properties().time()).sum();
        return new Route(new RouteProperties(type, 3000, total, steps.length / 2, fare == null ? null : new Fare(fare)), List.of(steps));
    }

    static KakaoRouteResponse ok(Route... routes) {
        return new KakaoRouteResponse("OK", List.of(routes));
    }

    @Test
    void subwayRouteBecomesStopsWithDirectionsAndOffsets() {
        // 강남 -> 서초 (2호선), parenthesised names as Kakao writes them.
        var drafts = mapper.map(ok(route("SUBWAY", 1450, subway("2호선", 300, "강남", "교대(법원.검찰청)", "서초"))), 31, 33);

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.fare()).isEqualTo(1450);
            assertThat(draft.fareSource()).isEqualTo(FareSource.KAKAO);
            assertThat(draft.totalTimeSec()).isEqualTo(300);
            assertThat(draft.steps()).singleElement().satisfies(step -> {
                assertThat(step.type()).isEqualTo("SUBWAY");
                assertThat(step.guidance()).isEqualTo("2호선 탑승, 2개 역 이동 후 서초 하차");
                assertThat(step.line().id()).isEqualTo(2);
                assertThat(step.directionCode()).isEqualTo(DirectionCode.INNER);
                assertThat(step.stops()).extracting(PathStop::stationId).containsExactly(31L, 32L, 33L);
                assertThat(step.stops()).extracting(PathStop::stationName).containsExactly("강남", "교대", "서초");
                assertThat(step.stops()).extracting(PathStop::offsetSec).containsExactly(0, 150, 300);
                // The alighting stop keeps the direction of the last pair.
                assertThat(step.stops()).extracting(PathStop::direction).containsOnly(DirectionCode.INNER);
                assertThat(step.path()).hasSize(2);
            });
        });
    }

    @Test
    void transferRouteKeepsBothNodesAndWalking() {
        var drafts = mapper.map(ok(route("SUBWAY", 1550, subway("2호선", 120, "강남", "교대"), step("WALKING", 90),
                subway("3호선", 100, "교대", "고속터미널"))), 31, 40);

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.steps()).extracting(RouteDraft.StepDraft::type).containsExactly("SUBWAY", "WALKING", "SUBWAY");
            assertThat(draft.subwayStops()).extracting(PathStop::stationLineId).containsExactly(1L, 2L, 4L, 5L);
            assertThat(draft.subwayStops()).extracting(PathStop::offsetSec).containsExactly(0, 120, 210, 310);
            assertThat(draft.subwayStops()).extracting(PathStop::direction)
                    .containsExactly(DirectionCode.INNER, DirectionCode.INNER, DirectionCode.DOWN, DirectionCode.DOWN);
            assertThat(draft.walkingSeconds()).isEqualTo(90);
        });
    }

    @Test
    void routesThatCannotBeMappedAreDropped() {
        Route good = route("SUBWAY", 1450, subway("2호선", 300, "강남", "교대", "서초"));
        List<Route> bad = new ArrayList<>(List.of(
                route("BUS_AND_SUBWAY", null, subway("2호선", 300, "강남", "교대", "서초")),       // route type
                route("SUBWAY", 1450, step("BUS", 300)),                                          // bus step
                route("SUBWAY", 1450, subway("9호선", 300, "강남", "교대", "서초")),               // line outside 1~8
                route("SUBWAY", 1450, subway("2호선", 300, "강남", "없는역", "서초")),             // unknown station
                route("SUBWAY", 1450, subway("2호선", 300, "강남", "서초")),                       // not adjacent (no RIDE edge)
                route("SUBWAY", 1450, subway("2호선", 120, "교대", "서초"))));                     // does not start at the origin
        for (Route route : bad) {
            assertThat(mapper.map(ok(route), 31, 33)).as(route.toString()).isEmpty();
        }
        List<Route> mixed = new ArrayList<>(bad);
        mixed.add(good);
        assertThat(mapper.map(new KakaoRouteResponse("OK", mixed), 31, 33)).hasSize(1);
    }

    @Test
    void emptyOrFailedResponsesGiveNoRoute() {
        assertThat(mapper.map(new KakaoRouteResponse("OK", List.of()), 31, 33)).isEmpty();
        assertThat(mapper.map(new KakaoRouteResponse("STARTNODES_NULL", null), 31, 33)).isEmpty();
        assertThat(mapper.map(null, 31, 33)).isEmpty();
    }

    static Step walk(String guidance, int time) {
        return new Step(new StepProperties("WALKING", guidance, 80, time, List.of(), List.of()), null);
    }

    @Test
    void transferWalkWithTheKakaoNameInFrontKeepsOnlyOurs() {
        // Real Kakao guidance: "{Kakao name}{our name + 역} 환승".
        assertThat(KakaoRouteMapper.walkingGuidance("시청시청역 환승", "시청", "시청")).isEqualTo("시청역 환승");
        assertThat(KakaoRouteMapper.walkingGuidance("동대문역사문화공원(DDP)동대문역사문화공원역 환승", "동대문역사문화공원(DDP)", "동대문역사문화공원"))
                .isEqualTo("동대문역사문화공원역 환승");
        // A name already ending in 역 gets no second 역.
        assertThat(KakaoRouteMapper.walkingGuidance("서울역서울역 환승", "서울역", "서울역")).isEqualTo("서울역 환승");
        // Already fine: unchanged.
        assertThat(KakaoRouteMapper.walkingGuidance("시청역 환승", "시청", "시청")).isEqualTo("시청역 환승");
        // No SUBWAY step before: unchanged.
        assertThat(KakaoRouteMapper.walkingGuidance("시청시청역 환승", null, null)).isEqualTo("시청시청역 환승");
        // Another station's name is never shortened: only the alighting station counts.
        assertThat(KakaoRouteMapper.walkingGuidance("종각종각역 환승", "시청", "시청")).isEqualTo("종각종각역 환승");
        // A name with a repeated syllable ("다다") written once stays; the doubled form loses only the Kakao copy.
        assertThat(KakaoRouteMapper.walkingGuidance("다다역 환승", "다다", "다다")).isEqualTo("다다역 환승");
        assertThat(KakaoRouteMapper.walkingGuidance("다다다다역 환승", "다다", "다다")).isEqualTo("다다역 환승");
        // Only the exact transfer phrase: other text after the names is left as Kakao wrote it.
        assertThat(KakaoRouteMapper.walkingGuidance("시청시청역 환승 (2번 출구)", "시청", "시청")).isEqualTo("시청시청역 환승 (2번 출구)");
        assertThat(KakaoRouteMapper.walkingGuidance(null, "시청", "시청")).isNull();
    }

    @Test
    void transferWalkGuidanceIsFixedInMappedRoutes() {
        var drafts = mapper.map(ok(route("SUBWAY", 1550, subway("2호선", 120, "강남", "교대"), walk("교대교대역 환승", 90),
                subway("3호선", 100, "교대", "고속터미널"))), 31, 40);
        assertThat(drafts).singleElement().satisfies(draft ->
                assertThat(draft.steps().get(1).guidance()).isEqualTo("교대역 환승"));

        // Kakao writes its own name (with the parenthesised part) in front of ours.
        var kakaoName = mapper.map(ok(route("SUBWAY", 1550, subway("2호선", 120, "강남", "교대(법원.검찰청)"),
                walk("교대(법원.검찰청)교대역 환승", 90), subway("3호선", 100, "교대", "고속터미널"))), 31, 40);
        assertThat(kakaoName).singleElement().satisfies(draft ->
                assertThat(draft.steps().get(1).guidance()).isEqualTo("교대역 환승"));

        // A walk before the first ride keeps Kakao's text.
        var leading = mapper.map(ok(route("SUBWAY", 1450, walk("강남강남역 1번 출구", 60), subway("2호선", 300, "강남", "교대", "서초"))), 31, 33);
        assertThat(leading).singleElement().satisfies(draft ->
                assertThat(draft.steps().get(0).guidance()).isEqualTo("강남강남역 1번 출구"));
    }

    @Test
    void missingKakaoFareIsEstimatedFromOurDistance() {
        var drafts = mapper.map(ok(route("SUBWAY", null, subway("2호선", 300, "강남", "교대", "서초"))), 31, 33);
        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.fareSource()).isEqualTo(FareSource.ESTIMATED);
            assertThat(draft.fare()).isEqualTo(1550); // 2,500 m
        });
    }
}
