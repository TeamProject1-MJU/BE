package com.mju.linkro.subway.graph;

import java.util.Map;
import java.util.Set;

/**
 * Junctions where one line splits (#56) and the arm pairs a single train runs through.
 *
 * <p>An arm is the side of the junction a RIDE edge leads to: a branch code (SEONGSU, SINJEONG, MACHEON,
 * EUNGAM_LOOP) or {@code MAIN-}/{@code MAIN+} for the main line side with a lower/higher seq than the junction.
 * Moving between two arms not listed here needs a train change at a known junction. Junctions missing from this
 * table are logged as WARN and treated as through running.
 */
final class BranchRules {
    static final String MAIN_LOWER = "MAIN-";
    static final String MAIN_HIGHER = "MAIN+";

    /** Junction (station name, line code) -> arm pairs served by one train. */
    private static final Map<Junction, Set<Set<String>>> THROUGH = Map.of(
            new Junction("성수", "2"), Set.of(pair(MAIN_LOWER, MAIN_HIGHER)),
            new Junction("신도림", "2"), Set.of(pair(MAIN_LOWER, MAIN_HIGHER)),
            // Trains run 방화~하남검단산 and 방화~마천, so only 마천 <-> 하남검단산 needs a change.
            new Junction("강동", "5"), Set.of(pair(MAIN_LOWER, MAIN_HIGHER), pair(MAIN_LOWER, "MACHEON")),
            // The Eungam loop and the main line are one train: main -> loop, and loop -> loop when going round again.
            new Junction("응암", "6"), Set.of(pair(MAIN_HIGHER, "EUNGAM_LOOP"), pair("EUNGAM_LOOP", "EUNGAM_LOOP")));

    record Junction(String stationName, String lineCode) {}

    private BranchRules() {}

    static boolean known(Junction junction) {
        return THROUGH.containsKey(junction);
    }

    /** Unknown junctions run through. */
    static boolean through(Junction junction, String fromArm, String toArm) {
        Set<Set<String>> pairs = THROUGH.get(junction);
        return pairs == null || pairs.contains(pair(fromArm, toArm));
    }

    private static Set<String> pair(String a, String b) {
        return a.equals(b) ? Set.of(a) : Set.of(a, b);
    }
}
