package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.repository.RecommendationRepository.NeighbourRow;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NearbyStationsTest {
    private static List<String> classify(List<NeighbourRow> rows) {
        return NearbyStations.classify(rows).stream().map(Object::toString).toList();
    }

    private static NeighbourRow row(long station, long line, int seq, String branch, int neighbourSeq, String neighbourBranch) {
        return new NeighbourRow(station, "역" + station, line, seq, branch, neighbourSeq, neighbourBranch);
    }

    @Test
    void lowerSeqIsPrevAndHigherSeqIsNext() {
        assertThat(classify(List.of(row(10, 1, 5, "MAIN", 4, "MAIN"), row(12, 1, 5, "MAIN", 6, "MAIN"))))
                .containsExactly("Neighbour[stationId=10, name=역10, lineId=1, relation=PREV]",
                        "Neighbour[stationId=12, name=역12, lineId=1, relation=NEXT]");
    }

    @Test
    void endOfALineHasOneNeighbour() {
        assertThat(classify(List.of(row(11, 1, 1, "MAIN", 2, "MAIN")))).containsExactly("Neighbour[stationId=11, name=역11, lineId=1, relation=NEXT]");
    }

    @Test
    void loopSeamNeighbourAcrossTheSeamTakesTheOtherRelation() {
        // Node at seq 1 of a loop of 43: seq 2 is NEXT, seq 43 is across the seam, so PREV.
        assertThat(classify(List.of(row(20, 2, 1, "MAIN", 2, "MAIN"), row(30, 2, 1, "MAIN", 43, "MAIN"))))
                .containsExactly("Neighbour[stationId=20, name=역20, lineId=2, relation=NEXT]",
                        "Neighbour[stationId=30, name=역30, lineId=2, relation=PREV]");
        // And the last node: seq 42 is PREV, seq 1 is across the seam, so NEXT.
        assertThat(classify(List.of(row(31, 2, 43, "MAIN", 42, "MAIN"), row(21, 2, 43, "MAIN", 1, "MAIN"))))
                .containsExactly("Neighbour[stationId=31, name=역31, lineId=2, relation=PREV]",
                        "Neighbour[stationId=21, name=역21, lineId=2, relation=NEXT]");
    }

    @Test
    void transferStationListsEachLineAndJunctionKeepsEveryNeighbour() {
        var result = classify(List.of(
                row(40, 2, 10, "MAIN", 9, "MAIN"), row(41, 2, 10, "MAIN", 11, "MAIN"), row(42, 2, 10, "MAIN", 1, "SEONGSU"),
                row(50, 4, 3, "MAIN", 2, "MAIN"), row(51, 4, 3, "MAIN", 4, "MAIN")));
        assertThat(result).hasSize(5);
        assertThat(result).contains("Neighbour[stationId=50, name=역50, lineId=4, relation=PREV]",
                "Neighbour[stationId=51, name=역51, lineId=4, relation=NEXT]",
                "Neighbour[stationId=42, name=역42, lineId=2, relation=PREV]");
    }
}
