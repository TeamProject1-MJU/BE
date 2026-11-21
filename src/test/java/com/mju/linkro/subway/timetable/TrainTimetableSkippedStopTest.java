package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.timetable.TrainTimetableTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #187: a stop pair that is not a RIDE edge gets the stations between when the train has at least three stops, one way
 * in one direction joins them and none of them is a stop of the train already. Otherwise it stays unmapped.
 */
class TrainTimetableSkippedStopTest {
    // 5호선 가(1) -> 나(2) -> 다(3) -> 라(4) going DOWN (60, 120, 60 s) and back UP; 마(5) joins 가 and 다 too (DOWN, 100 s
    // each), so 가 -> 다 has two ways.
    static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new Line(5, "5", "5호선", "#996CAC", 5)),
            List.of(station(1, "가"), station(2, "나"), station(3, "다"), station(4, "라"), station(5, "마")),
            List.of(new Node(501, 1, 5, "501", 1, "MAIN"), new Node(502, 2, 5, "502", 2, "MAIN"), new Node(503, 3, 5, "503", 3, "MAIN"),
                    new Node(504, 4, 5, "504", 4, "MAIN"), new Node(505, 5, 5, "505", 5, "MAIN")),
            List.of(edge(1, 501, 502, DirectionCode.DOWN, 60), edge(2, 502, 503, DirectionCode.DOWN, 120),
                    edge(3, 503, 504, DirectionCode.DOWN, 60), edge(4, 504, 503, DirectionCode.UP, 60),
                    edge(5, 503, 502, DirectionCode.UP, 120), edge(6, 502, 501, DirectionCode.UP, 60),
                    edge(7, 501, 505, DirectionCode.DOWN, 100), edge(8, 505, 503, DirectionCode.DOWN, 100)));

    static Edge edge(long id, long from, long to, DirectionCode direction, int seconds) {
        return new Edge(id, from, to, StepType.RIDE, direction, seconds, 1000);
    }

    static String stop(String code, String station, int arrive, int depart) {
        return row("5호선", station, "평일", "일반", code, arrive, depart, "가", "라");
    }

    /** X stops at 다 for 20 s: the median dwell there. */
    static final String[] DWELL_AT_DA = {stop("X", "나", 2000, 2000), stop("X", "다", 2120, 2140), stop("X", "라", 2200, 2200)};

    static TrainTimetable load(String... rows) {
        String[] all = new String[rows.length + DWELL_AT_DA.length];
        System.arraycopy(DWELL_AT_DA, 0, all, 0, DWELL_AT_DA.length);
        System.arraycopy(rows, 0, all, DWELL_AT_DA.length, rows.length);
        return TrainTimetableTest.load(DATA, all);
    }

    @Test
    void aTrainOfThreeStopsGetsTheStationItSkips() {
        // T leaves 나 at 1090 and reaches 라 at 1300 without 다: 210 s for 180 s of rides, so 다 gets its 20 s median dwell
        // and the remaining 190 s of running split 120:60 -> 다 at 1090 + 127 = 1217, leaving 1237, 라 at 1300.
        TrainTimetable timetable = load(stop("T", "가", 1000, 1000), stop("T", "나", 1060, 1090), stop("T", "라", 1300, 1300));
        assertThat(timetable.departures(2, DayType.WEEKDAY)).filteredOn(d -> d.train().trainCode().equals("T"))
                .extracting(Departure::departSec, Departure::arriveSec).containsExactly(org.assertj.core.groups.Tuple.tuple(1090, 1217));
        assertThat(timetable.departures(3, DayType.WEEKDAY)).filteredOn(d -> d.train().trainCode().equals("T"))
                .extracting(Departure::departSec, Departure::arriveSec).containsExactly(org.assertj.core.groups.Tuple.tuple(1237, 1300));
        // Times keep rising and add up: 1090 -> 1217 -> 1237 -> 1300 is the 210 s of the timetable.
        TrainTimetable.LoadReport report = timetable.report();
        assertThat(report.pairsSplit()).containsExactly(Map.entry("5호선 나->라", 1));
        assertThat(report.pairsDropped()).isEmpty();
        assertThat(report.pairsNotInGraph()).containsExactly(Map.entry("5호선 나->라", 1));
        assertThat(report.rides()).isEqualTo(2 + 3);
    }

    @Test
    void withoutTimeToSpareTheStationGetsNoDwell() {
        // 나 1090 -> 라 1270: exactly the 180 s of rides, so 다 is passed at the ride seconds' share (1090 + 120).
        TrainTimetable timetable = load(stop("T", "가", 1000, 1000), stop("T", "나", 1060, 1090), stop("T", "라", 1270, 1270));
        assertThat(timetable.departures(2, DayType.WEEKDAY)).filteredOn(d -> d.train().trainCode().equals("T"))
                .extracting(Departure::arriveSec).containsExactly(1210);
        assertThat(timetable.departures(3, DayType.WEEKDAY)).filteredOn(d -> d.train().trainCode().equals("T"))
                .extracting(Departure::departSec).containsExactly(1210);
    }

    @Test
    void aTwoStopTrainStaysUnmapped() {
        // Like 5호선 59xx (상일동 -> 강동): only its two ends, maybe a train to or from the depot.
        TrainTimetable timetable = load(stop("D", "나", 1000, 1000), stop("D", "라", 1210, 1210));
        assertThat(timetable.departures(2, DayType.WEEKDAY)).noneMatch(d -> d.train().trainCode().equals("D"));
        assertThat(timetable.departures(3, DayType.WEEKDAY)).noneMatch(d -> d.train().trainCode().equals("D"));
        assertThat(timetable.report().pairsDropped()).containsExactly(Map.entry("5호선 나->라", 1));
        assertThat(timetable.report().pairsSplit()).isEmpty();
    }

    @Test
    void aPairJoinedByTwoWaysStaysUnmapped() {
        // 가 -> 다 runs through 나 or through 마: which one the train took is unknown.
        TrainTimetable timetable = load(stop("W", "가", 1000, 1000), stop("W", "다", 1200, 1220), stop("W", "라", 1280, 1280));
        assertThat(timetable.departures(1, DayType.WEEKDAY)).noneMatch(d -> d.train().trainCode().equals("W"));
        assertThat(timetable.departures(7, DayType.WEEKDAY)).noneMatch(d -> d.train().trainCode().equals("W"));
        assertThat(timetable.departures(3, DayType.WEEKDAY)).anyMatch(d -> d.train().trainCode().equals("W"));
        assertThat(timetable.report().pairsDropped()).containsExactly(Map.entry("5호선 가->다", 1));
    }

    @Test
    void aStationAlreadyAStopOfTheTrainIsNotAddedAgain() {
        // V stops at 나, 라 and then 다 (going back UP): 나 -> 라 would put 다 in twice.
        TrainTimetable timetable = load(stop("V", "나", 1000, 1000), stop("V", "라", 1200, 1230), stop("V", "다", 1290, 1290));
        assertThat(timetable.departures(2, DayType.WEEKDAY)).noneMatch(d -> d.train().trainCode().equals("V"));
        assertThat(timetable.departures(4, DayType.WEEKDAY)).anyMatch(d -> d.train().trainCode().equals("V"));
        assertThat(timetable.report().pairsDropped()).containsExactly(Map.entry("5호선 나->라", 1));
    }
}
