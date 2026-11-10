package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.repository.RecommendationRepository.NeighbourRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * PREV / NEXT of the stations next to the best station on each line (API 8.1 nearbyAlternatives).
 *
 * <p>A neighbour with a lower seq is PREV and one with a higher seq is NEXT. On the line 2 loop the node at the
 * seam has both neighbours on the same side of its seq; the farther one is across the seam, so it takes the other
 * relation. At a branch junction every neighbour is kept and takes PREV or NEXT by its seq like any other. The API
 * spec says "2호선 순환·지선 분기도 같은 규칙" without defining PREV/NEXT there; this is the repo's own reading.
 */
final class NearbyStations {
    static final String PREV = "PREV";
    static final String NEXT = "NEXT";

    record Neighbour(long stationId, String name, long lineId, String relation) {}

    private NearbyStations() {}

    static List<Neighbour> classify(List<NeighbourRow> rows) {
        List<Neighbour> result = new ArrayList<>();
        rows.stream().map(NeighbourRow::lineId).distinct().forEach(lineId -> {
            List<NeighbourRow> line = rows.stream().filter(row -> row.lineId() == lineId).toList();
            List<NeighbourRow> sameBranch = line.stream()
                    .filter(row -> Objects.equals(row.branchCode(), row.neighbourBranch())).toList();
            // Seam of a loop: exactly two neighbours of the same branch, both above or both below this node's seq.
            NeighbourRow seamFlip = null;
            if (sameBranch.size() == 2 && sameBranch.stream().map(row -> row.neighbourSeq() > row.seq()).distinct().count() == 1) {
                seamFlip = sameBranch.stream().max(Comparator.comparingInt(row -> Math.abs(row.neighbourSeq() - row.seq()))).orElseThrow();
            }
            for (NeighbourRow row : line) {
                boolean higher = row.neighbourSeq() > row.seq();
                if (row == seamFlip) {
                    higher = !higher;
                }
                result.add(new Neighbour(row.neighbourStationId(), row.neighbourName(), row.lineId(), higher ? NEXT : PREV));
            }
        });
        return result;
    }
}
