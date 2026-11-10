package com.mju.linkro.recommendation.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC access to recommendation_runs / recommendation_results (V6) plus the two station queries only the
 * recommendation needs. JSON columns travel as text and are cast to jsonb here; mapping is the service's job.
 */
@Repository
public class RecommendationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public RecommendationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RunRow(UUID id, UUID roomId, UUID requestedBy, String criteriaJson, Instant createdAt) {}

    public record ResultRow(UUID id, UUID runId, String criterion, long stationId, BigDecimal metricValue,
                            String tripsJson, String nearbyJson, String reason, String reasonSource) {}

    /**
     * One RIDE neighbour of a station on one line.
     *
     * @param lineId          the line shared with the origin station
     * @param seq             seq of the origin station's node on that line
     * @param branchCode      branch of that node
     * @param neighbourSeq    seq of the neighbour's node
     * @param neighbourBranch branch of the neighbour's node
     */
    public record NeighbourRow(long neighbourStationId, String neighbourName, long lineId, int seq, String branchCode,
                               int neighbourSeq, String neighbourBranch) {}

    /** The run and its result rows in one short transaction. */
    @Transactional
    public void save(RunRow run, List<ResultRow> results) {
        jdbc.update("""
                insert into recommendation_runs (id, room_id, requested_by, criteria, created_at)
                values (:id, :roomId, :requestedBy, cast(:criteria as jsonb), :createdAt)
                """, new MapSqlParameterSource().addValue("id", run.id()).addValue("roomId", run.roomId())
                .addValue("requestedBy", run.requestedBy()).addValue("criteria", run.criteriaJson())
                .addValue("createdAt", Timestamp.from(run.createdAt())));
        for (ResultRow result : results) {
            jdbc.update("""
                    insert into recommendation_results
                        (id, run_id, criterion, station_id, metric_value, trips, nearby, reason, reason_source)
                    values (:id, :runId, :criterion, :stationId, :metricValue, cast(:trips as jsonb),
                            cast(:nearby as jsonb), :reason, :reasonSource)
                    """, new MapSqlParameterSource().addValue("id", result.id()).addValue("runId", result.runId())
                    .addValue("criterion", result.criterion()).addValue("stationId", result.stationId())
                    .addValue("metricValue", result.metricValue()).addValue("trips", result.tripsJson())
                    .addValue("nearby", result.nearbyJson()).addValue("reason", result.reason())
                    .addValue("reasonSource", result.reasonSource()));
        }
    }

    /** API 8.2: ORDER BY created_at DESC, id DESC LIMIT 1 of the room's runs. */
    public Optional<RunRow> latest(UUID roomId) {
        return jdbc.query(RUN_SELECT + " where room_id = :roomId order by created_at desc, id desc limit 1",
                Map.of("roomId", roomId), RecommendationRepository::run).stream().findFirst();
    }

    public Optional<RunRow> find(UUID runId) {
        return jdbc.query(RUN_SELECT + " where id = :id", Map.of("id", runId), RecommendationRepository::run)
                .stream().findFirst();
    }

    public List<ResultRow> results(UUID runId) {
        return jdbc.query("""
                select id, run_id, criterion, station_id, metric_value, cast(trips as text) as trips,
                       cast(nearby as text) as nearby, reason, reason_source
                from recommendation_results where run_id = :runId
                """, Map.of("runId", runId), (rs, n) -> new ResultRow(rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getString("criterion"), rs.getLong("station_id"),
                rs.getBigDecimal("metric_value"), rs.getString("trips"), rs.getString("nearby"),
                rs.getString("reason"), rs.getString("reason_source")));
    }

    /** Supported stations by name; a name shared by several stations maps to all of their ids. */
    public Map<String, List<Long>> supportedStationIdsByName() {
        Map<String, List<Long>> byName = new HashMap<>();
        jdbc.query("select id, name from stations where is_supported order by id", rs -> {
            byName.computeIfAbsent(rs.getString("name"), name -> new ArrayList<>()).add(rs.getLong("id"));
        });
        return byName;
    }

    /** RIDE neighbours on the same line(s) of a station, supported stations only, in either direction of travel. */
    public List<NeighbourRow> neighbours(long stationId) {
        return jdbc.query("""
                select distinct nb.station_id as neighbour_station_id, s.name as neighbour_name, sl.line_id,
                       sl.seq, sl.branch_code, nb.seq as neighbour_seq, nb.branch_code as neighbour_branch
                from station_lines sl
                join station_connections c on c.connection_type = 'RIDE'
                    and (c.from_station_line_id = sl.id or c.to_station_line_id = sl.id)
                join station_lines nb on nb.id = case when c.from_station_line_id = sl.id
                    then c.to_station_line_id else c.from_station_line_id end
                join stations s on s.id = nb.station_id
                where sl.station_id = :stationId and nb.line_id = sl.line_id and nb.station_id <> sl.station_id
                    and s.is_supported
                order by sl.line_id, nb.seq, nb.station_id
                """, Map.of("stationId", stationId), (rs, n) -> new NeighbourRow(rs.getLong("neighbour_station_id"),
                rs.getString("neighbour_name"), rs.getLong("line_id"), rs.getInt("seq"), rs.getString("branch_code"),
                rs.getInt("neighbour_seq"), rs.getString("neighbour_branch")));
    }

    private static final String RUN_SELECT =
            "select id, room_id, requested_by, cast(criteria as text) as criteria, created_at from recommendation_runs";

    private static RunRow run(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getObject("requested_by", UUID.class), rs.getString("criteria"),
                rs.getTimestamp("created_at").toInstant());
    }
}
