package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.congestion.HolidayCalendar;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock.ServiceTime;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import com.mju.linkro.subway.timetable.TrainTimetable.TrainKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TrainTimetableTest {
    // 2호선: 뚝섬(1) - 성수(2) - 건대입구(3), 성수지선 성수 - 용답(4). 5호선: 길동(5) - 강동(6) - 둔촌동(7, 마천지선).
    // 7호선 총신대입구(이수)(8) - 남성(9). 1호선 시청(10) - 종각(11).
    static final SubwayGraphData DATA = new SubwayGraphData(
            List.of(new Line(1, "1", "1호선", "#0052A4", 1), new Line(2, "2", "2호선", "#00A84D", 2),
                    new Line(5, "5", "5호선", "#996CAC", 5), new Line(7, "7", "7호선", "#747F00", 7)),
            List.of(station(1, "뚝섬"), station(2, "성수"), station(3, "건대입구"), station(4, "용답"), station(5, "길동"),
                    station(6, "강동"), station(7, "둔촌동"), station(8, "총신대입구(이수)"), station(9, "남성"),
                    station(10, "시청"), station(11, "종각"), new Station(12, "미지원", 37.5, 127.0, false)),
            List.of(new Node(101, 1, 2, "210", 10, "MAIN"), new Node(102, 2, 2, "211", 11, "MAIN"),
                    new Node(103, 3, 2, "212", 12, "MAIN"), new Node(104, 4, 2, "211-1", 1, "SEONGSU"),
                    new Node(105, 5, 5, "2549", 49, "MAIN"), new Node(106, 6, 5, "2548", 48, "MAIN"),
                    new Node(107, 7, 5, "2552", 52, "MACHEON"), new Node(108, 8, 7, "2738", 28, "MAIN"),
                    new Node(109, 9, 7, "2739", 29, "MAIN"), new Node(110, 10, 1, "151", 2, "MAIN"),
                    new Node(111, 11, 1, "152", 3, "MAIN"), new Node(112, 12, 2, "299", 99, "MAIN")),
            List.of(ride(1, 101, 102, DirectionCode.INNER), ride(2, 102, 101, DirectionCode.OUTER),
                    ride(3, 102, 103, DirectionCode.INNER), ride(4, 103, 102, DirectionCode.OUTER),
                    ride(5, 102, 104, DirectionCode.DOWN), ride(6, 104, 102, DirectionCode.UP),
                    ride(7, 106, 105, DirectionCode.DOWN), ride(8, 106, 107, DirectionCode.DOWN),
                    ride(9, 107, 106, DirectionCode.UP), ride(10, 109, 108, DirectionCode.UP),
                    ride(11, 110, 111, DirectionCode.UP), ride(12, 103, 112, DirectionCode.INNER),
                    new Edge(13, 102, 104, StepType.TRANSFER, null, 19, 23)));

    static final String HEADER = "line,station,daytype,direction,express,train_code,arrive_sec,depart_sec,origin,terminal";

    static Station station(long id, String name) {
        return new Station(id, name, 37.5, 127.0, true);
    }

    static Edge ride(long id, long from, long to, DirectionCode direction) {
        return new Edge(id, from, to, StepType.RIDE, direction, 90, 1000);
    }

    static String row(String line, String station, String day, String express, String code, int arrive, int depart,
                      String origin, String terminal) {
        return String.join(",", line, station, day, "하행", express, code, String.valueOf(arrive), String.valueOf(depart), origin, terminal);
    }

    static TrainTimetable load(String... rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write((HEADER + "\n" + String.join("\n", rows) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return TrainTimetable.load(new ByteArrayInputStream(bytes.toByteArray()), DATA);
    }

    /** Three 2호선 trains 뚝섬 -> 성수 -> 건대입구 on weekdays (뚝섬 departs 21600, 22200, 22800). */
    static TrainTimetable innerLine() {
        return load(
                row("2호선", "뚝섬", "평일", "일반", "2001", 21600, 21600, "뚝섬", "건대입구"),
                row("2호선", "성수", "평일", "일반", "2001", 21690, 21720, "뚝섬", "건대입구"),
                row("2호선", "건대입구", "평일", "일반", "2001", 21810, 21810, "뚝섬", "건대입구"),
                row("2호선", "뚝섬", "평일", "일반", "2003", 22200, 22200, "뚝섬", "건대입구"),
                row("2호선", "성수", "평일", "일반", "2003", 22290, 22320, "뚝섬", "건대입구"),
                row("2호선", "건대입구", "평일", "일반", "2003", 22410, 22410, "뚝섬", "건대입구"),
                row("2호선", "뚝섬", "평일", "일반", "2005", 22800, 22800, "뚝섬", "건대입구"),
                row("2호선", "성수", "평일", "일반", "2005", 22890, 22920, "뚝섬", "건대입구"),
                row("2호선", "건대입구", "평일", "일반", "2005", 23010, 23010, "뚝섬", "건대입구"));
    }

    @Test
    void nextDepartureBoundaries() {
        TrainTimetable timetable = innerLine();
        // Exactly at a departure: that train.
        assertThat(timetable.nextDeparture(1, DayType.WEEKDAY, 22200)).get().extracting(Departure::departSec).isEqualTo(22200);
        // One second before: the same train. One second after: the next one.
        assertThat(timetable.nextDeparture(1, DayType.WEEKDAY, 22199)).get().extracting(Departure::departSec).isEqualTo(22200);
        assertThat(timetable.nextDeparture(1, DayType.WEEKDAY, 22201)).get().extracting(Departure::departSec).isEqualTo(22800);
        // Before the first train: the first train.
        assertThat(timetable.nextDeparture(1, DayType.WEEKDAY, 0)).get().extracting(Departure::departSec).isEqualTo(21600);
        // After the last train, another day type, an edge without trains: nothing.
        assertThat(timetable.nextDeparture(1, DayType.WEEKDAY, 22801)).isEmpty();
        assertThat(timetable.nextDeparture(1, DayType.SATURDAY, 21600)).isEmpty();
        assertThat(timetable.nextDeparture(2, DayType.WEEKDAY, 21600)).isEmpty();
        assertThat(timetable.nextDeparture(999, DayType.WEEKDAY, 21600)).isEmpty();

        Departure ride = timetable.nextDeparture(3, DayType.WEEKDAY, 22000).orElseThrow();
        assertThat(ride.departSec()).isEqualTo(22320);
        assertThat(ride.arriveSec()).isEqualTo(22410);
        assertThat(ride.train()).isEqualTo(new TrainKey(DayType.WEEKDAY, "2", "2003", "뚝섬", "건대입구"));
    }

    @Test
    void lastDepartureArrivingBoundaries() {
        TrainTimetable timetable = innerLine();
        // 뚝섬 -> 성수 arrivals: 21690, 22290, 22890.
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 22290)).get().extracting(Departure::departSec).isEqualTo(22200);
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 22289)).get().extracting(Departure::departSec).isEqualTo(21600);
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 22291)).get().extracting(Departure::departSec).isEqualTo(22200);
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 99999)).get().extracting(Departure::departSec).isEqualTo(22800);
        // Before the first arrival: nothing.
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 21689)).isEmpty();
        assertThat(timetable.lastDepartureArriving(1, DayType.SUNDAY, 99999)).isEmpty();
    }

    @Test
    void lastDepartureArrivingPicksTheLatestDepartureWhenAFasterTrainOvertakes() {
        TrainTimetable timetable = load(
                row("2호선", "뚝섬", "평일", "일반", "A", 21600, 21600, "뚝섬", "성수"),
                row("2호선", "성수", "평일", "일반", "A", 21900, 21900, "뚝섬", "성수"),
                row("2호선", "뚝섬", "평일", "일반", "B", 21700, 21700, "뚝섬", "성수"),
                row("2호선", "성수", "평일", "일반", "B", 21800, 21800, "뚝섬", "성수"));
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 21900)).get()
                .extracting(departure -> departure.train().trainCode()).isEqualTo("B");
        assertThat(timetable.lastDepartureArriving(1, DayType.WEEKDAY, 21850)).get()
                .extracting(departure -> departure.train().trainCode()).isEqualTo("B");
    }

    @Test
    void timesAfterMidnightCountPast86400() {
        // 24:50 -> 24:51:30 on the same service day.
        TrainTimetable timetable = load(
                row("2호선", "뚝섬", "토요일", "일반", "2999", 89340, 89400, "뚝섬", "건대입구"),
                row("2호선", "성수", "토요일", "일반", "2999", 89490, 89520, "뚝섬", "건대입구"),
                row("2호선", "뚝섬", "토요일", "일반", "2997", 86000, 86000, "뚝섬", "건대입구"),
                row("2호선", "성수", "토요일", "일반", "2997", 86090, 86100, "뚝섬", "건대입구"));
        assertThat(timetable.nextDeparture(1, DayType.SATURDAY, 86001)).get().extracting(Departure::departSec, Departure::arriveSec)
                .containsExactly(89400, 89490);
        // 00:10 written as 600 is a different, early-morning second: no train then.
        assertThat(timetable.nextDeparture(1, DayType.SATURDAY, 89401)).isEmpty();
        assertThat(timetable.lastDepartureArriving(1, DayType.SATURDAY, 89490)).get().extracting(Departure::departSec).isEqualTo(89400);
        assertThat(timetable.lastDepartureArriving(1, DayType.SATURDAY, 89489)).get().extracting(Departure::departSec).isEqualTo(86000);
    }

    @Test
    void branchEdgesAreMappedFromTheStopOrder() {
        // 5호선: one train 길동 <- 강동 (본선), one 강동 -> 둔촌동 (마천지선), one 둔촌동 -> 강동. All labelled 하행 in the
        // row helper: the direction column is not used.
        TrainTimetable timetable = load(
                row("5호선", "강동", "평일", "일반", "5101", 30000, 30030, "방화", "하남검단산"),
                row("5호선", "길동", "평일", "일반", "5101", 30120, 30150, "방화", "하남검단산"),
                row("5호선", "강동", "평일", "일반", "5201", 30300, 30330, "방화", "마천"),
                row("5호선", "둔촌동", "평일", "일반", "5201", 30420, 30450, "방화", "마천"),
                row("5호선", "둔촌동", "평일", "일반", "5202", 31000, 31030, "마천", "방화"),
                row("5호선", "강동", "평일", "일반", "5202", 31120, 31150, "마천", "방화"),
                // 성수지선 성수 -> 용답.
                row("2호선", "성수", "평일", "일반", "2501", 32000, 32030, "성수", "신설동"),
                row("2호선", "용답", "평일", "일반", "2501", 32150, 32180, "성수", "신설동"));
        assertThat(timetable.nextDeparture(7, DayType.WEEKDAY, 0)).get().extracting(d -> d.train().trainCode()).isEqualTo("5101");
        assertThat(timetable.nextDeparture(8, DayType.WEEKDAY, 0)).get().extracting(d -> d.train().trainCode()).isEqualTo("5201");
        assertThat(timetable.nextDeparture(9, DayType.WEEKDAY, 0)).get().extracting(d -> d.train().trainCode()).isEqualTo("5202");
        assertThat(timetable.nextDeparture(5, DayType.WEEKDAY, 0)).get().extracting(Departure::departSec, Departure::arriveSec)
                .containsExactly(32030, 32150);
        // The TRANSFER edge 성수 -> 용답 (13) gets nothing.
        assertThat(timetable.edgesWithTrains()).containsExactlyInAnyOrder(5L, 7L, 8L, 9L);
        assertThat(timetable.report().pairsNotInGraph()).isEmpty();
    }

    @Test
    void lookupByLineStationAndNextStation() {
        TrainTimetable timetable = innerLine();
        assertThat(timetable.rideEdgeId(2, 2, 3)).hasValue(3);
        assertThat(timetable.nextDeparture(2, 2, 3, DayType.WEEKDAY, 21721)).get().extracting(Departure::departSec).isEqualTo(22320);
        assertThat(timetable.lastDepartureArriving(2, 2, 3, DayType.WEEKDAY, 22409)).get().extracting(Departure::departSec).isEqualTo(21720);
        // Not neighbours, wrong line, unknown station, unsupported station: no edge.
        assertThat(timetable.rideEdgeId(2, 1, 3)).isEmpty();
        assertThat(timetable.rideEdgeId(5, 2, 3)).isEmpty();
        assertThat(timetable.rideEdgeId(2, 2, 999)).isEmpty();
        assertThat(timetable.rideEdgeId(2, 3, 12)).isEmpty();
        assertThat(timetable.nextDeparture(2, 1, 3, DayType.WEEKDAY, 0)).isEmpty();
    }

    @Test
    void trainsSharingACodeAreKeptApartByOriginAndTerminal() {
        // 2호선 2101 runs 뚝섬 -> 성수 on the main line and, as another train, 성수 -> 용답 on the 성수 branch. Keyed by
        // code alone the stops would merge (뚝섬 21600, 성수 21660, 성수 21700, 용답): 뚝섬 -> 성수 would arrive at the
        // branch train's 21650 and 성수 -> 용답 would leave at the main train's 21700.
        TrainTimetable timetable = load(
                row("2호선", "뚝섬", "평일", "일반", "2101", 21600, 21600, "뚝섬", "성수"),
                row("2호선", "성수", "평일", "일반", "2101", 21690, 21700, "뚝섬", "성수"),
                row("2호선", "성수", "평일", "일반", "2101", 21650, 21660, "성수", "신설동"),
                row("2호선", "용답", "평일", "일반", "2101", 21780, 21810, "성수", "신설동"));
        assertThat(timetable.report().trains()).isEqualTo(2);
        assertThat(timetable.departures(1, DayType.WEEKDAY)).singleElement()
                .isEqualTo(new Departure(21600, 21690, new TrainKey(DayType.WEEKDAY, "2", "2101", "뚝섬", "성수")));
        assertThat(timetable.departures(5, DayType.WEEKDAY)).singleElement()
                .isEqualTo(new Departure(21660, 21780, new TrainKey(DayType.WEEKDAY, "2", "2101", "성수", "신설동")));
        assertThat(timetable.report().pairsNotInGraph()).isEmpty();
    }

    @Test
    void aLoopTrainPassingAStationTwiceFollowsItsDepartures() {
        // 성수 -> 건대입구 ... -> 뚝섬 -> 성수 -> 건대입구: the second 성수 visit is a new lap.
        TrainTimetable timetable = load(
                row("2호선", "성수", "평일", "일반", "2019", 20000, 20000, "성수", "성수"),
                row("2호선", "건대입구", "평일", "일반", "2019", 20090, 20120, "성수", "성수"),
                row("2호선", "뚝섬", "평일", "일반", "2019", 25000, 25030, "성수", "성수"),
                row("2호선", "성수", "평일", "일반", "2019", 25120, 25150, "성수", "성수"),
                row("2호선", "건대입구", "평일", "일반", "2019", 25240, 25270, "성수", "성수"));
        assertThat(timetable.departures(3, DayType.WEEKDAY)).extracting(Departure::departSec).containsExactly(20000, 25150);
        assertThat(timetable.departures(1, DayType.WEEKDAY)).extracting(Departure::departSec).containsExactly(25030);
        // 건대입구 -> 뚝섬 is no RIDE edge here: reported, not mapped.
        assertThat(timetable.report().pairsNotInGraph()).containsExactly(Map.entry("2호선 건대입구->뚝섬", 1));
    }

    @Test
    void reportCountsSkippedRows() {
        TrainTimetable timetable = load(
                row("2호선", "뚝섬", "평일", "급행", "E1", 21600, 21600, "뚝섬", "성수"),
                row("2호선", "성수", "평일", "급행", "E1", 21690, 21690, "뚝섬", "성수"),
                row("2호선", "없는역", "평일", "일반", "2001", 21500, 21530, "없는역", "성수"),
                row("2호선", "없는역", "토요일", "일반", "2001", 21500, 21530, "없는역", "성수"),
                row("2호선", "미지원", "평일", "일반", "2001", 21560, 21570, "없는역", "성수"),
                row("2호선", "뚝섬", "평일", "일반", "2001", 21600, 21600, "없는역", "성수"),
                // arrive_sec 0 at 성수: its departure is used as the arrival.
                row("2호선", "성수", "평일", "일반", "2001", 0, 21700, "없는역", "성수"),
                // 7호선 이수 is our 총신대입구(이수); 1호선 시청 matches as is.
                row("7호선", "남성", "평일", "일반", "7001", 21000, 21030, "온수", "장암"),
                row("7호선", "이수", "평일", "일반", "7001", 21120, 21150, "온수", "장암"),
                row("1호선", "시청", "평일", "일반", "K1", 21000, 21030, "서울역", "청량리"),
                row("1호선", "종각", "평일", "일반", "K1", 21120, 21150, "서울역", "청량리"));
        TrainTimetable.LoadReport report = timetable.report();
        assertThat(report.rows()).isEqualTo(11);
        assertThat(report.expressSkipped()).isEqualTo(2);
        assertThat(report.unmappedRows()).isEqualTo(3);
        assertThat(report.unmappedStations()).containsExactly(Map.entry("2호선 미지원", 1), Map.entry("2호선 없는역", 2));
        assertThat(report.missingArrivals()).isEqualTo(1);
        assertThat(timetable.departures(1, DayType.WEEKDAY)).singleElement().extracting(Departure::arriveSec).isEqualTo(21700);
        assertThat(timetable.departures(10, DayType.WEEKDAY)).singleElement().extracting(Departure::departSec).isEqualTo(21030);
        assertThat(timetable.departures(11, DayType.WEEKDAY)).singleElement().extracting(Departure::arriveSec).isEqualTo(21120);
        assertThat(report.rideEdges()).isEqualTo(11);
        assertThat(report.rideEdgesWithTrains()).isEqualTo(3);
    }

    @Test
    void malformedFilesFail() {
        assertThatThrownBy(() -> TrainTimetable.load(new ByteArrayInputStream(gzip("line,station\n")), DATA))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("header");
        assertThatThrownBy(() -> load(row("2호선", "뚝섬", "주말", "일반", "1", 1, 1, "a", "b")))
                .hasMessageContaining("unknown daytype");
        assertThatThrownBy(() -> load(row("2호선", "뚝섬", "평일", "일반", "1", 1, 1, "a", "b") + ",extra"))
                .hasMessageContaining("columns");
        assertThatThrownBy(() -> load("2호선,뚝섬,평일,하행,일반,1,x,1,a,b")).hasMessageContaining("integers");
    }

    @Test
    void clockGivesTheDayTypeAndServiceSecond() {
        TimetableClock clock = new TimetableClock(new HolidayCalendar("2026-12-25"));
        // 2026-11-13 Friday, 11-14 Saturday, 11-15 Sunday, 12-25 Friday holiday.
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 13, 8, 0)))
                .isEqualTo(new ServiceTime(LocalDate.of(2026, 11, 13), DayType.WEEKDAY, 28800));
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 14, 8, 0)).dayType()).isEqualTo(DayType.SATURDAY);
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 15, 8, 0)).dayType()).isEqualTo(DayType.SUNDAY);
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 12, 25, 8, 0)).dayType()).isEqualTo(DayType.SUNDAY);
        // After midnight: the day before, past 86400. Saturday 00:30 is Friday's 88200.
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 14, 0, 30)))
                .isEqualTo(new ServiceTime(LocalDate.of(2026, 11, 13), DayType.WEEKDAY, 88200));
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 12, 26, 0, 10)).dayType()).isEqualTo(DayType.SUNDAY);
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 14, 2, 59, 59)).secondOfServiceDay()).isEqualTo(86400 + 10799);
        // 03:00 starts the new service day.
        assertThat(clock.serviceTimeOf(LocalDateTime.of(2026, 11, 14, 3, 0)))
                .isEqualTo(new ServiceTime(LocalDate.of(2026, 11, 14), DayType.SATURDAY, 10800));
        assertThat(clock.dayTypeOf(LocalDate.of(2026, 12, 25))).isEqualTo(DayType.SUNDAY);
    }

    @Test
    void bundledFileHasTheExpectedShape() throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(TrainTimetableConfig.RESOURCE)) {
            assertThat(in).as("src/main/resources/" + TrainTimetableConfig.RESOURCE).isNotNull();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(in), StandardCharsets.UTF_8));
            assertThat(reader.readLine()).isEqualTo(TrainTimetable.HEADER);
        }
    }

    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
