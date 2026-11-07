package com.mju.linkro.subway.congestion.load;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

/**
 * Turns AI rows into congestion_stats rows without touching the DB, and counts everything that is skipped.
 * Values are kept as given: pct as BigDecimal from the string, level/label/day_type unchanged; direction only through
 * {@link DirectionMappings}.
 */
final class CongestionLoadPlan {
    static final Set<LocalTime> ALLOWED_SLOTS = allowedSlots();
    /** ck_cong_day values; with the 39 slots, the 117 cells an estimation rule tries to fill. */
    static final List<String> DAY_TYPES = List.of("WEEKDAY", "SATURDAY", "SUNDAY");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** Graph node, its station_lines.branch_code and the directions of the RIDE edges that touch it in the seed. */
    record Node(long stationLineId, String branchCode, Set<String> directions) {}

    record Row(long stationLineId, String direction, String dayType, LocalTime slotStart, BigDecimal pct,
               String level, String label, String source, String modelVersion) {}

    record Key(long stationLineId, String direction, String dayType, LocalTime slotStart) {}

    final List<Row> rows = new ArrayList<>();
    /** ESTIMATED rows from {@link Estimations}; never mixed into {@link #rows}. */
    final List<Row> estimated = new ArrayList<>();
    /** Cells of an estimation rule left empty because a neighbour has no row for that direction, day type and slot. */
    int estimationSkipped;
    int read;
    int otherSlot;
    final Map<String, Integer> unmatched = new TreeMap<>();
    final Map<String, Integer> directionMismatch = new TreeMap<>();
    /** Loaded rows whose direction a mapping rule changed, per rule report label (every label listed, 0 if unused). */
    final Map<String, Integer> mapped = new LinkedHashMap<>();

    static CongestionLoadPlan of(List<Map<String, String>> csv, Map<String, Node> nodes, String modelVersion) {
        return of(csv, nodes, modelVersion, DirectionMappings.load());
    }

    static CongestionLoadPlan of(List<Map<String, String>> csv, Map<String, Node> nodes, String modelVersion,
                                 DirectionMappings mappings) {
        return of(csv, nodes, modelVersion, mappings, Estimations.load());
    }

    /**
     * @param nodes      (line code, normalized station name) -> node, from {@link #key(String, String)}
     * @param modelVersion every row must carry this model_version (from the manifest)
     */
    static CongestionLoadPlan of(List<Map<String, String>> csv, Map<String, Node> nodes, String modelVersion,
                                 DirectionMappings mappings, Estimations estimations) {
        CongestionLoadPlan plan = new CongestionLoadPlan();
        mappings.reports().forEach(report -> plan.mapped.put(report, 0));
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
            Optional<DirectionMappings.Rule> rule = mappings.find(node, direction);
            if (rule.isPresent()) {
                direction = rule.get().to();
            }
            if (!node.directions().contains(direction)) {
                plan.directionMismatch.merge(line + "호선 " + direction, 1, Integer::sum);
                continue;
            }
            Key unique = new Key(node.stationLineId(), direction, row.get("day_type"), slot);
            if (!seen.add(unique)) {
                throw new IllegalArgumentException("Two rows map to the same congestion key " + unique + " (" + line + "호선 " + name + ")");
            }
            rule.ifPresent(applied -> plan.mapped.merge(applied.report(), 1, Integer::sum));
            plan.rows.add(new Row(node.stationLineId(), direction, row.get("day_type"), slot,
                    new BigDecimal(row.get("congestion_pct").strip()), row.get("congestion_level"),
                    row.get("congestion_level_label"), "PREDICTED", row.get("model_version")));
        }
        estimations.rules().forEach(rule -> plan.estimate(rule, nodes));
        return plan;
    }

    /**
     * Fills the rule's target only when the AI file has no row for that (node, direction): each (day type, slot) gets the
     * exact mean of both neighbours' pct, or is skipped and counted when either neighbour lacks it.
     */
    private void estimate(Estimations.Rule rule, Map<String, Node> nodes) {
        Node target = node(nodes, rule.lineCode(), rule.station());
        Node previous = node(nodes, rule.lineCode(), rule.previous());
        Node next = node(nodes, rule.lineCode(), rule.next());
        String direction = rule.direction();
        if (!target.directions().contains(direction)) {
            throw new IllegalArgumentException("estimation target " + rule.lineCode() + "호선 " + rule.station() + " has no " + direction + " edge");
        }
        if (rows.stream().anyMatch(row -> row.stationLineId() == target.stationLineId() && row.direction().equals(direction))) {
            return;
        }
        Map<Key, BigDecimal> pct = new HashMap<>();
        for (Row row : rows) {
            pct.put(new Key(row.stationLineId(), row.direction(), row.dayType(), row.slotStart()), row.pct());
        }
        for (String dayType : DAY_TYPES) {
            for (LocalTime slot : ALLOWED_SLOTS) {
                BigDecimal before = pct.get(new Key(previous.stationLineId(), direction, dayType, slot));
                BigDecimal after = pct.get(new Key(next.stationLineId(), direction, dayType, slot));
                if (before == null || after == null) {
                    estimationSkipped++;
                    continue;
                }
                // Halving a decimal always terminates, so the mean is exact.
                BigDecimal mean = before.add(after).divide(TWO);
                CongestionLevels.Level level = CongestionLevels.of(mean);
                estimated.add(new Row(target.stationLineId(), direction, dayType, slot, mean, level.code(), level.label(), "ESTIMATED", null));
            }
        }
    }

    private static Node node(Map<String, Node> nodes, String lineCode, String station) {
        Node node = nodes.get(key(lineCode, StationNames.normalize(station)));
        if (node == null) throw new IllegalArgumentException("estimation rule names unknown station " + lineCode + "호선 " + station);
        return node;
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
