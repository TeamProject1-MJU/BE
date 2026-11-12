package com.mju.linkro.route.service;

import com.mju.linkro.route.dto.RouteSearchResponse.FareSource;
import com.mju.linkro.route.dto.RouteSearchResponse.Line;
import com.mju.linkro.route.service.RouteDraft.StepDraft;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.PathStop;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.mock.MockFareCalculatorAccess;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TrainTimetable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.route.service.KakaoRouteMapperTest.ok;
import static com.mju.linkro.route.service.KakaoRouteMapperTest.route;
import static com.mju.linkro.route.service.KakaoRouteMapperTest.step;
import static com.mju.linkro.route.service.KakaoRouteMapperTest.subway;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fixed routes on a hand-made timetable (#120): 2호선 강남(1) -> 교대(2) -> 서초(3) and 3호선 교대(4) -> 고속터미널(5),
 * transfer at 교대. 2026-11-12 is a Thursday, 2026-11-14 a Saturday.
 */
class FirstTrainTimerTest {
    static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new SubwayGraphData.Line(2, "2", "2호선", "#00A84D", 2), new SubwayGraphData.Line(3, "3", "3호선", "#EF7C1C", 3)),
            List.of(new SubwayGraphData.Station(31, "강남", 37.49, 127.02, true), new SubwayGraphData.Station(32, "교대", 37.49, 127.01, true),
                    new SubwayGraphData.Station(33, "서초", 37.49, 127.00, true), new SubwayGraphData.Station(40, "고속터미널", 37.50, 127.00, true)),
            List.of(new SubwayGraphData.Node(1, 31, 2, "222", 1, "MAIN"), new SubwayGraphData.Node(2, 32, 2, "223", 2, "MAIN"),
                    new SubwayGraphData.Node(3, 33, 2, "224", 3, "MAIN"), new SubwayGraphData.Node(4, 32, 3, "340", 1, "MAIN"),
                    new SubwayGraphData.Node(5, 40, 3, "339", 2, "MAIN")),
            List.of(new SubwayGraphData.Edge(1, 1, 2, StepType.RIDE, DirectionCode.INNER, 120, 1200),
                    new SubwayGraphData.Edge(2, 2, 3, StepType.RIDE, DirectionCode.INNER, 120, 1300),
                    new SubwayGraphData.Edge(4, 4, 5, StepType.RIDE, DirectionCode.DOWN, 100, 1000),
                    new SubwayGraphData.Edge(5, 2, 4, StepType.TRANSFER, null, 60, 80)));

    static final String HEADER = "line,station,daytype,direction,express,train_code,arrive_sec,depart_sec,origin,terminal";

    /** {@code stops} as "역:도착초:출발초". */
    static List<String> train(String day, String line, String code, String... stops) {
        List<String> rows = new ArrayList<>();
        for (String stop : stops) {
            String[] f = stop.split(":");
            rows.add(String.join(",", line, f[0], day, "외선", "일반", code, f[1], f[2], "강남", "서초"));
        }
        return rows;
    }

    static int sec(int hour, int minute) {
        return hour * 3600 + minute * 60;
    }

    // 2호선 weekday: first 05:36, then 14:03, last 24:46 (all reach 서초 4 minutes later, 교대 at +2 min).
    static final List<List<String>> WEEKDAY_2 = List.of(
            train("평일", "2호선", "W1", "강남:" + sec(5, 36) + ":" + sec(5, 36), "교대:" + sec(5, 38) + ":" + sec(5, 38), "서초:" + sec(5, 40) + ":" + sec(5, 40)),
            train("평일", "2호선", "W2", "강남:" + sec(14, 3) + ":" + sec(14, 3), "교대:" + sec(14, 5) + ":" + sec(14, 5), "서초:" + sec(14, 7) + ":" + sec(14, 7)),
            train("평일", "2호선", "W3", "강남:" + sec(23, 33) + ":" + sec(23, 33), "교대:" + sec(23, 35) + ":" + sec(23, 35), "서초:" + sec(23, 37) + ":" + sec(23, 37)),
            train("평일", "2호선", "W4", "강남:" + sec(24, 46) + ":" + sec(24, 46), "교대:" + sec(24, 48) + ":" + sec(24, 48), "서초:" + sec(24, 50) + ":" + sec(24, 50)));
    // 2호선 Saturday: first 05:35.
    static final List<String> SATURDAY_2 = train("토요일", "2호선", "S1",
            "강남:" + sec(5, 35) + ":" + sec(5, 35), "교대:" + sec(5, 37) + ":" + sec(5, 37), "서초:" + sec(5, 39) + ":" + sec(5, 39));
    // 3호선 weekday 교대 -> 고속터미널: first 05:50, last 24:20 (before the last 2호선 train reaches 교대).
    static final List<List<String>> WEEKDAY_3 = List.of(
            train("평일", "3호선", "T1", "교대:" + sec(5, 50) + ":" + sec(5, 50), "고속터미널:" + sec(5, 52) + ":" + sec(5, 52)),
            train("평일", "3호선", "T2", "교대:" + sec(14, 10) + ":" + sec(14, 10), "고속터미널:" + sec(14, 12) + ":" + sec(14, 12)),
            train("평일", "3호선", "T3", "교대:" + sec(24, 20) + ":" + sec(24, 20), "고속터미널:" + sec(24, 22) + ":" + sec(24, 22)));

    static TrainTimetable timetable() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        List<List<String>> all = new ArrayList<>(WEEKDAY_2);
        all.add(SATURDAY_2);
        all.addAll(WEEKDAY_3);
        all.forEach(rows -> rows.forEach(row -> csv.append(row).append('\n')));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(csv.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA);
    }

    static final FirstTrainTimer TIMER = new FirstTrainTimer(timetable(), new TimetableClock(new HolidayCalendar("")));

    static Instant seoul(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant();
    }

    static PathStop stop(long station, String name, long node, long line, DirectionCode direction, int offset) {
        return new PathStop(station, name, 37.5, 127.0, node, line, direction, offset);
    }

    /** INTERNAL-shaped 강남 -> 서초 on 2호선. */
    static RouteDraft gangnamToSeocho() {
        List<PathStop> stops = List.of(stop(31, "강남", 1, 2, DirectionCode.INNER, 0), stop(32, "교대", 2, 2, DirectionCode.INNER, 150),
                stop(33, "서초", 3, 2, DirectionCode.INNER, 300));
        return new RouteDraft(List.of(new StepDraft(RouteDraft.SUBWAY, "2호선", 300, 2500, new Line(2, "2호선", "#00A84D"),
                DirectionCode.INNER, stops, null)), 300, 1550, FareSource.ESTIMATED, 0, 31, 33);
    }

    /** 강남 -> 교대 on 2호선, 60 s walk, 교대 -> 고속터미널 on 3호선. */
    static RouteDraft gangnamToExpressTerminal() {
        StepDraft line2 = new StepDraft(RouteDraft.SUBWAY, "2호선", 150, 1200, new Line(2, "2호선", "#00A84D"), DirectionCode.INNER,
                List.of(stop(31, "강남", 1, 2, DirectionCode.INNER, 0), stop(32, "교대", 2, 2, DirectionCode.INNER, 150)), null);
        StepDraft walk = new StepDraft(RouteDraft.WALKING, "교대역 3호선 환승", 60, 80, null, null, List.of(), null);
        StepDraft line3 = new StepDraft(RouteDraft.SUBWAY, "3호선", 120, 1000, new Line(3, "3호선", "#EF7C1C"), DirectionCode.DOWN,
                List.of(stop(32, "교대", 4, 3, DirectionCode.DOWN, 210), stop(40, "고속터미널", 5, 3, DirectionCode.DOWN, 330)), null);
        return new RouteDraft(List.of(line2, walk, line3), 330, 1550, FareSource.ESTIMATED, 1, 31, 40);
    }

    @Test
    void weekdayDawnMovesToTheFirstTrain() {
        for (String at : List.of("2026-11-12T03:00:00", "2026-11-12T05:00:00")) {
            var timing = TIMER.time(gangnamToSeocho(), seoul(at)).orElseThrow();
            assertThat(timing.noService()).as(at).isTrue();
            assertThat(timing.departure()).as(at).isEqualTo(seoul("2026-11-12T05:36:00"));
            assertThat(timing.boardAt()).as(at).isEqualTo(seoul("2026-11-12T05:36:00"));
        }
    }

    @Test
    void saturdayCalendarNightAfterFridaysLastTrainTakesSaturdaysFirstTrain() {
        // Saturday 01:30 belongs to Friday's (weekday) service day, after its last train: Saturday trains from 05:35.
        var timing = TIMER.time(gangnamToSeocho(), seoul("2026-11-14T01:30:00")).orElseThrow();
        assertThat(timing.noService()).isTrue();
        assertThat(timing.departure()).isEqualTo(seoul("2026-11-14T05:35:00"));
    }

    @Test
    void afterTheLastTrainTakesTheNextServiceDaysFirstTrain() {
        // Thursday's service day at Friday 00:50, after the 24:46 last train: Friday (weekday) 05:36.
        var timing = TIMER.time(gangnamToSeocho(), seoul("2026-11-13T00:50:00")).orElseThrow();
        assertThat(timing.noService()).isTrue();
        assertThat(timing.departure()).isEqualTo(seoul("2026-11-13T05:36:00"));
    }

    @Test
    void runningHoursKeepTheDeparture() {
        for (String at : List.of("2026-11-12T14:00:00", "2026-11-12T23:30:00", "2026-11-13T00:40:00", "2026-11-12T05:36:00")) {
            var timing = TIMER.time(gangnamToSeocho(), seoul(at)).orElseThrow();
            assertThat(timing.noService()).as(at).isFalse();
            assertThat(timing.departure()).as(at).isEqualTo(seoul(at));
        }
    }

    @Test
    void aTransferLineThatHasStoppedMovesTheWholeRouteToTheFirstTrains() {
        // 2호선 still runs at 00:30 (24:46), but 3호선 left 교대 for the last time at 24:20.
        var timing = TIMER.time(gangnamToExpressTerminal(), seoul("2026-11-13T00:30:00")).orElseThrow();
        assertThat(timing.noService()).isTrue();
        assertThat(timing.departure()).isEqualTo(seoul("2026-11-13T05:36:00"));
        // At 14:00 both lines run.
        assertThat(TIMER.time(gangnamToExpressTerminal(), seoul("2026-11-12T14:00:00")).orElseThrow().noService()).isFalse();
    }

    @Test
    void aRouteOffTheTimetableIsNotJudged() {
        // 서초 -> 교대 is no RIDE edge of the timetable: neither day can ride it, so nothing is moved or flagged.
        StepDraft back = new StepDraft(RouteDraft.SUBWAY, "2호선", 120, 1200, new Line(2, "2호선", "#00A84D"), DirectionCode.OUTER,
                List.of(stop(33, "서초", 3, 2, DirectionCode.OUTER, 0), stop(32, "교대", 2, 2, DirectionCode.OUTER, 120)), null);
        RouteDraft draft = new RouteDraft(List.of(back), 120, 1550, FareSource.ESTIMATED, 0, 33, 32);
        assertThat(TIMER.time(draft, seoul("2026-11-12T03:00:00"))).isEmpty();
        assertThat(TIMER.time(new RouteDraft(List.of(), 0, 0, FareSource.ESTIMATED, 0, 31, 31), seoul("2026-11-12T03:00:00"))).isEmpty();
    }

    @Test
    void kakaoRoutesAreTimedOnTheirMappedStops() {
        // Kakao fixture (no Kakao call): a 120 s walk, then 2호선 강남 -> 교대 -> 서초 as Kakao names them.
        RouteReference reference = mock(RouteReference.class);
        when(reference.index()).thenReturn(RouteReference.Index.of(DATA));
        KakaoRouteMapper mapper = new KakaoRouteMapper(reference, MockFareCalculatorAccess.calculator());
        List<RouteDraft> drafts = mapper.map(ok(route("SUBWAY", 1450, step("WALKING", 120),
                subway("2호선", 300, "강남", "교대(법원.검찰청)", "서초"))), 31, 33);
        RouteDraft kakao = drafts.get(0);
        assertThat(kakao.subwayStops()).extracting(PathStop::stationLineId).containsExactly(1L, 2L, 3L);

        var dawn = TIMER.time(kakao, seoul("2026-11-12T03:00:00")).orElseThrow();
        assertThat(dawn.noService()).isTrue();
        assertThat(dawn.boardAt()).isEqualTo(seoul("2026-11-12T05:36:00"));
        // The walk before the first stop is kept: leave 2 minutes before the first train.
        assertThat(dawn.departure()).isEqualTo(seoul("2026-11-12T05:34:00"));

        var afternoon = TIMER.time(kakao, seoul("2026-11-12T14:00:00")).orElseThrow();
        assertThat(afternoon.noService()).isFalse();
        assertThat(afternoon.departure()).isEqualTo(seoul("2026-11-12T14:00:00"));
    }
}
