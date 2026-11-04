package com.mju.linkro.subway.congestion.load;

import com.mju.linkro.subway.congestion.load.CongestionLoadPlan.Node;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads AI congestion predictions into congestion_stats as PREDICTED rows, plus ESTIMATED rows from
 * {@link Estimations}, in one transaction.
 * Upserts on uq_cong_slot_source, so running it again with the same file changes no row count.
 */
public final class CongestionLoader {
    private final Connection connection;

    public CongestionLoader(Connection connection) {
        this.connection = connection;
    }

    /** Result counts printed by the tool. */
    public record Report(int read, int otherSlot, Map<String, Integer> unmatched,
                         Map<String, Integer> directionMismatch, Map<String, Integer> mapped,
                         int estimated, int estimationSkipped, int loaded, int inserted, int updated, String modelVersion) {

        public String format() {
            StringBuilder out = new StringBuilder();
            out.append("model_version: ").append(modelVersion).append('\n');
            out.append("read rows: ").append(read).append('\n');
            out.append("excluded other slots: ").append(otherSlot).append('\n');
            out.append("node match failures: ").append(unmatched.values().stream().mapToInt(Integer::intValue).sum()).append('\n');
            unmatched.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(20).forEach(entry -> out.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n'));
            out.append("direction mismatches: ").append(directionMismatch.values().stream().mapToInt(Integer::intValue).sum()).append('\n');
            directionMismatch.forEach((key, count) -> out.append("  ").append(key).append(": ").append(count).append('\n'));
            mapped.forEach((report, count) -> out.append("mapped ").append(report).append(": ").append(count).append('\n'));
            out.append("estimated rows: ").append(estimated).append(" (skipped ").append(estimationSkipped).append(")\n");
            out.append("loaded rows: ").append(loaded).append(" (new ").append(inserted).append(", updated ").append(updated).append(")\n");
            return out.toString();
        }
    }

    /**
     * Checks the CSV against the manifest SHA-256 and returns the manifest model_version. Needs no database, so the
     * tool runs it before reading connection settings or connecting.
     */
    public static String verify(Path csv, Path manifest) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(manifest));
        String expected = root.path("output_sha256").path(csv.getFileName().toString()).asString(null);
        if (expected == null) {
            throw new IllegalArgumentException("manifest has no output_sha256 entry for " + csv.getFileName());
        }
        String actual = sha256(csv);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("SHA-256 mismatch for " + csv.getFileName() + ": manifest " + expected + ", file " + actual);
        }
        String modelVersion = root.path("model_version").asString(null);
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("manifest has no model_version");
        }
        return modelVersion;
    }

    /** Verifies again (the file must not change between check and load), then loads in one transaction. */
    public Report load(Path csv, Path manifest) throws IOException, SQLException {
        String modelVersion = verify(csv, manifest);
        CongestionLoadPlan plan = CongestionLoadPlan.of(CongestionCsv.read(csv), nodes(), modelVersion);
        List<CongestionLoadPlan.Row> rows = new ArrayList<>(plan.rows);
        rows.addAll(plan.estimated);
        int[] counts = upsert(rows);
        return new Report(plan.read, plan.otherSlot, plan.unmatched, plan.directionMismatch,
                plan.mapped, plan.estimated.size(), plan.estimationSkipped, rows.size(), counts[0], counts[1], modelVersion);
    }

    /** (line code, normalized name) -> node with its branch code and the directions of its RIDE edges. */
    Map<String, Node> nodes() throws SQLException {
        Map<String, Long> ids = new HashMap<>();
        Map<Long, Set<String>> directions = new HashMap<>();
        Map<Long, String> branches = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     select sl.id, sl.branch_code, l.line_code, s.name, c.direction
                     from station_lines sl
                     join stations s on s.id = sl.station_id
                     join subway_lines l on l.id = sl.line_id
                     left join station_connections c on c.connection_type = 'RIDE'
                          and (c.from_station_line_id = sl.id or c.to_station_line_id = sl.id)
                     order by sl.id
                     """)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String key = CongestionLoadPlan.key(rs.getString("line_code"), StationNames.normalize(rs.getString("name")));
                Long previous = ids.putIfAbsent(key, id);
                if (previous != null && previous != id) {
                    throw new IllegalStateException("Two nodes normalize to " + key);
                }
                branches.put(id, rs.getString("branch_code"));
                Set<String> set = directions.computeIfAbsent(id, k -> new TreeSet<>());
                if (rs.getString("direction") != null) set.add(rs.getString("direction"));
            }
        }
        Map<String, Node> nodes = new HashMap<>();
        ids.forEach((key, id) -> nodes.put(key, new Node(id, branches.get(id), Set.copyOf(directions.get(id)))));
        return nodes;
    }

    /** Stages rows in a temp table and upserts them in one statement; returns {inserted, updated}. Rolls back on any error. */
    private int[] upsert(List<CongestionLoadPlan.Row> rows) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        create temp table congestion_load (
                            station_line_id bigint, direction varchar(5), day_type varchar(8), slot_start time,
                            congestion_pct numeric, congestion_level varchar(12), congestion_level_label varchar(10),
                            source varchar(9), model_version varchar(20)) on commit drop
                        """);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "insert into congestion_load values (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int batch = 0;
                for (var row : rows) {
                    insert.setLong(1, row.stationLineId());
                    insert.setString(2, row.direction());
                    insert.setString(3, row.dayType());
                    insert.setObject(4, row.slotStart());
                    insert.setBigDecimal(5, row.pct());
                    insert.setString(6, row.level());
                    insert.setString(7, row.label());
                    insert.setString(8, row.source());
                    insert.setString(9, row.modelVersion());
                    insert.addBatch();
                    if (++batch % 1000 == 0) insert.executeBatch();
                }
                insert.executeBatch();
            }
            int inserted = 0;
            int updated = 0;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("""
                         insert into congestion_stats (station_line_id, direction, day_type, slot_start, congestion_pct,
                                                       congestion_level, congestion_level_label, source, model_version)
                         select station_line_id, direction, day_type, slot_start, congestion_pct,
                                congestion_level, congestion_level_label, source, model_version
                         from congestion_load
                         on conflict on constraint uq_cong_slot_source do update set
                             congestion_pct = excluded.congestion_pct,
                             congestion_level = excluded.congestion_level,
                             congestion_level_label = excluded.congestion_level_label,
                             model_version = excluded.model_version
                         returning (xmax = 0) as inserted
                         """)) {
                while (rs.next()) {
                    if (rs.getBoolean("inserted")) inserted++; else updated++;
                }
            }
            connection.commit();
            return new int[] {inserted, updated};
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
