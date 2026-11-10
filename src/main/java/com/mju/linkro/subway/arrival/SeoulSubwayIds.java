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
 * </ul>
 */
public final class SeoulSubwayIds {
    /** Our line_code -> subwayId. */
    static final Map<String, String> SUBWAY_IDS = Map.of("1", "1001", "2", "1002", "3", "1003", "4", "1004",
            "5", "1005", "6", "1006", "7", "1007", "8", "1008");
    /** Lines whose station_code gives the external station id (confirmed by observation). */
    static final Set<String> ID_LINES = Set.of("2");
    /**
     * Lines whose seed UP/DOWN is the opposite of Seoul's 상행/하행; arrival filtering flips them. Empty: line 1 was the
     * only one (#86) and V14 corrected its seed (#89). ArrivalApiPostgresTest.seedDirectionsMatchSeoulUpDown checks
     * every line, so a new entry here needs a reason there.
     */
    static final Set<String> SEED_DIRECTION_REVERSED = Set.of();
    /** Query names whose statnFid/statnTid do not follow the neighbour rule: no direction filter there. */
    static final Set<String> NO_DIRECTION_FILTER = Set.of("응암순환(상선)");

    private static final Map<String, DirectionCode> UPDN = Map.of("상행", DirectionCode.UP, "하행", DirectionCode.DOWN,
            "내선", DirectionCode.INNER, "외선", DirectionCode.OUTER);

    private SeoulSubwayIds() {}

    static Optional<String> subwayId(String lineCode) {
        return Optional.ofNullable(SUBWAY_IDS.get(lineCode));
    }

    /** External station id, only for {@link #ID_LINES} and a 3-digit code. */
    static Optional<String> stationId(String lineCode, String stationCode) {
        if (!ID_LINES.contains(lineCode) || stationCode == null || !stationCode.matches("\\d{3}")) return Optional.empty();
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
