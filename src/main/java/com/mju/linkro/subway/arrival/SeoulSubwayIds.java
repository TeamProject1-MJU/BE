package com.mju.linkro.subway.arrival;

import com.mju.linkro.subway.api.DirectionCode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Seoul realtime arrival API codes in one place (docs/arrivals-86.md).
 *
 * <ul>
 *   <li>subwayId: 1002, 1006, 1007 were seen on 2026-11-10; the 100N pattern for the other lines is unconfirmed.</li>
 *   <li>Station id "100{line}000{3-digit code}": seen for 2호선 211~213 and matches our 3-digit 2호선 station_code. Our
 *       5~8호선 codes have 4 digits (2611, 2729, ...) and do not match the ids seen (610~619, 726~728); 1·3·4호선
 *       are unconfirmed. Only {@link #ID_LINES} build an id.</li>
 *   <li>2호선 성수지선·신정지선 stations do not follow that pattern: {@link #LINE2_BRANCH_STATION_IDS} (#172).</li>
 * </ul>
 */
public final class SeoulSubwayIds {
    /** Our line_code -> subwayId. */
    static final Map<String, String> SUBWAY_IDS = Map.of("1", "1001", "2", "1002", "3", "1003", "4", "1004",
            "5", "1005", "6", "1006", "7", "1007", "8", "1008");
    /** Lines whose station_code gives the external station id (confirmed by observation). */
    static final Set<String> ID_LINES = Set.of("2");
    /**
     * 2호선 성수지선·신정지선: our station_code -> Seoul station id, measured on the Seoul realtime API on 2026-11-19
     * (#172). The main line follows "1002000{code}".
     */
    static final Map<String, String> LINE2_BRANCH_STATION_IDS = Map.of(
            "244", "1002002111", // 용답
            "245", "1002002112", // 신답
            "250", "1002002113", // 용두
            "246", "1002002114", // 신설동
            "247", "1002002341", // 도림천
            "248", "1002002342", // 양천구청
            "249", "1002002343", // 신정네거리
            "200", "1002002344"); // 까치산
    /**
     * Lines whose seed UP/DOWN is the opposite of Seoul's 상행/하행; arrival filtering flips them. Empty: line 1 was the
     * only one (#86) and V14 corrected its seed (#89). ArrivalApiPostgresTest.seedDirectionsMatchSeoulUpDown checks
     * every line, so a new entry here needs a reason there.
     */
    static final Set<String> SEED_DIRECTION_REVERSED = Set.of();
    /**
     * Query name of 6호선 응암, where trains entering the loop (toward 역촌) are 상행 "응암순환(상선)행" and 하행 trains
     * leave toward 새절 (operating answer of 2026-11-18, docs/arrivals-86.md). A LOOP request there keeps 상행 only (#164).
     */
    static final String LOOP_ENTRY_QUERY_NAME = "응암순환(상선)";
    /**
     * Lines whose branch rule is on (#174): a train whose destination lies on the other branch than the alighting station,
     * past a junction where the line splits in one direction, is dropped. 5호선 강동 (마천 / 하남검단산) is the only
     * such junction in the seed.
     */
    static final Set<String> BRANCH_FILTER_LINES = Set.of("5");
    /**
     * Lines whose short-turn rule is on (#174): with the first train's alighting station, trains ending before it are
     * dropped. Lines with regular short turns in the bundled timetable: 3호선 구파발·수서, 4호선 사당, 5호선 강동·상일동,
     * 6호선 봉화산·독바위·응암 (응암 loop, #177), 7호선 도봉산, 2호선 신도림·삼성 등 (circle, #177; 성수 always stays, see
     * {@link #RING_CIRCUIT_STATIONS}). Not 1호선 (destinations against the direction seen) or 8호선.
     */
    static final Set<String> SHORT_TURN_LINES = Set.of("2", "3", "4", "5", "6", "7");
    /**
     * Circular lines: the station where the circle trains' trips begin and end, never a short turn (#177). 2호선 trains
     * going round show "성수" as their destination (2026-11-19 observation; timetable 성수→성수 430 trains a weekday), so a
     * 성수 destination always stays.
     */
    static final Map<String, String> RING_CIRCUIT_STATIONS = Map.of("2", "성수");

    private static final Map<String, DirectionCode> UPDN = Map.of("상행", DirectionCode.UP, "하행", DirectionCode.DOWN,
            "내선", DirectionCode.INNER, "외선", DirectionCode.OUTER);

    private SeoulSubwayIds() {}

    static Optional<String> subwayId(String lineCode) {
        return Optional.ofNullable(SUBWAY_IDS.get(lineCode));
    }

    /** External station id, only for {@link #ID_LINES} and a 3-digit code; 2호선 branch stations by their own ids. */
    static Optional<String> stationId(String lineCode, String stationCode) {
        if (!ID_LINES.contains(lineCode) || stationCode == null || !stationCode.matches("\\d{3}")) return Optional.empty();
        if ("2".equals(lineCode) && LINE2_BRANCH_STATION_IDS.containsKey(stationCode)) {
            return Optional.of(LINE2_BRANCH_STATION_IDS.get(stationCode));
        }
        return Optional.of("100" + lineCode + "000" + stationCode);
    }

    /**
     * A seed direction (our graph, or a directionCode taken from it) in Seoul's terms: UP/DOWN swapped on
     * {@link #SEED_DIRECTION_REVERSED} lines, otherwise unchanged.
     */
    static DirectionCode seoulDirection(String lineCode, DirectionCode seedDirection) {
        if (seedDirection == null || !SEED_DIRECTION_REVERSED.contains(lineCode)) return seedDirection;
        return switch (seedDirection) {
            case UP -> DirectionCode.DOWN;
            case DOWN -> DirectionCode.UP;
            default -> seedDirection;
        };
    }

    /** updnLine (상행/하행/내선/외선) -> Seoul's direction, used as is in responses; null for anything else. */
    static DirectionCode direction(String updnLine) {
        return updnLine == null ? null : UPDN.get(updnLine.strip());
    }
}
