package com.mju.linkro.subway.congestion.load;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

/**
 * Turns AI rows into congestion_stats rows without touching the DB, and counts everything that is skipped.
 * Values are kept as given: pct as BigDecimal from the string, level/label/day_type/direction unchanged.
 */
final class CongestionLoadPlan {
    static final Set<LocalTime> ALLOWED_SLOTS = allowedSlots();

    /** Graph node and the directions of the RIDE edges that touch it in the seed. */
    record Node(long stationLineId, Set<String> directions) {}

    record Row(long stationLineId, String direction, String dayType, LocalTime slotStart, BigDecimal pct,
               String level, String label, String modelVersion) {}

    record Key(long stationLineId, String direction, String dayType, LocalTime slotStart) {}

    final List<Row> rows = new ArrayList<>();
    int read;
    int otherSlot;
    final Map<String, Integer> unmatched = new TreeMap<>();
    final Map<String, Integer> directionMismatch = new TreeMap<>();
    /** Rows loaded with AI DOWN mapped to LOOP on loop-only nodes (6호선 응암순환). */
    int mappedDownToLoop;

    /** Nodes whose seed directions are exactly {LOOP}: the one-way 6호선 Eungam loop stations. */
    private static final Set<String> LOOP_ONLY = Set.of("LOOP");

    /**
     * @param nodes      (line code, normalized station name) -> node, from {@link #key(String, String)}
     * @param modelVersion every row must carry this model_version (from the manifest)
     */
    static CongestionLoadPlan of(List<Map<String, String>> csv, Map<String, Node> nodes, String modelVersion) {
        CongestionLoadPlan plan = new CongestionLoadPlan();
        Set<Key> seen = new HashSet<>();
        for (Map<String, String> row : csv) {
            plan.read++;
            if (!"PREDICTED".equals(row.get("source"))) {
                throw new IllegalArgumentException("Only source=PREDICTED rows can be loaded, found " + row.get("source"));
            }
            if (!modelVersion.equals(row.get("model_version"))) {
                throw new IllegalArgumentException("model_version " + row.get("model_version") + " differs from manifest " + modelVersion);
            }
            LocalTime slot = LocalTime.parse(row.get("slot_start"));
            if (!ALLOWED_SLOTS.contains(slot)) {
                plan.otherSlot++;
                continue;
            }
            String line = row.get("line_code").strip();
            String name = row.get("station_name").strip();
            Node node = nodes.get(key(line, StationNames.normalize(name)));
            if (node == null) {
                plan.unmatched.merge(line + "호선 " + name, 1, Integer::sum);
                continue;
            }
            String direction = row.get("direction").strip();
            boolean mapped = false;
            // The AI file has only DOWN for the loop stations; pending AI confirmation, read it as the loop direction.
            if (node.directions().equals(LOOP_ONLY) && "DOWN".equals(direction)) {
                direction = "LOOP";
                mapped = true;
            }
            if (!node.directions().contains(direction)) {
                plan.directionMismatch.merge(line + "호선 " + direction, 1, Integer::sum);
                continue;
            }
            Key unique = new Key(node.stationLineId(), direction, row.get("day_type"), slot);
            if (!seen.add(unique)) {
                throw new IllegalArgumentException("Two rows map to the same congestion key " + unique + " (" + line + "호선 " + name + ")");
            }
            if (mapped) {
                plan.mappedDownToLoop++;
            }
            plan.rows.add(new Row(node.stationLineId(), direction, row.get("day_type"), slot,
                    new BigDecimal(row.get("congestion_pct").strip()), row.get("congestion_level"),
                    row.get("congestion_level_label"), row.get("model_version")));
        }
        return plan;
    }

    static String key(String lineCode, String normalizedName) {
        return lineCode + "|" + normalizedName;
    }

    private static Set<LocalTime> allowedSlots() {
        Set<LocalTime> slots = new TreeSet<>();
        // 05:30 .. 23:30 is 37 slots; counting avoids wrapping past midnight.
        for (int i = 0; i < 37; i++) {
            slots.add(LocalTime.of(5, 30).plusMinutes(30L * i));
        }
        slots.add(LocalTime.MIDNIGHT);
        slots.add(LocalTime.of(0, 30));
        return Collections.unmodifiableSet(slots);
    }
}
