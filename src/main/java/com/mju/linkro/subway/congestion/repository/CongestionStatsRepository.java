package com.mju.linkro.subway.congestion.repository;

import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.DirectionCode;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only JDBC over congestion_stats and the station tables it needs. */
@Repository
public class CongestionStatsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CongestionStatsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All slots of one node, direction and day type. */
    public record SeriesKey(long stationLineId, DirectionCode direction, DayType dayType) {}

    /** One stored row; pct is the stored value, unrounded. */
    public record Stat(LocalTime slot, CongestionSource source, BigDecimal pct, String level, String label) {}

    public record Node(long stationLineId, long stationId, long lineId) {}

    /**
     * Every row of the given series in one query, keeping per slot only the highest-priority source (rule D:
     * PREDICTED > ESTIMATED > MOCK). Series without rows are absent from the map.
     */
    public Map<SeriesKey, Map<LocalTime, Stat>> series(Collection<SeriesKey> keys) {
        Map<SeriesKey, Map<LocalTime, Stat>> result = new HashMap<>();
        if (keys.isEmpty()) return result;
        List<Object[]> tuples = keys.stream().distinct()
                .map(key -> new Object[] {key.stationLineId(), key.direction().name(), key.dayType().name()}).toList();
        jdbc.query("""
                select station_line_id, direction, day_type, slot_start, source, congestion_pct, congestion_level,
                       congestion_level_label
                from congestion_stats
                where (station_line_id, direction, day_type) in (:keys)
                """, Map.of("keys", tuples), rs -> {
            SeriesKey key = new SeriesKey(rs.getLong("station_line_id"), DirectionCode.valueOf(rs.getString("direction")),
                    DayType.valueOf(rs.getString("day_type")));
            Stat stat = new Stat(rs.getObject("slot_start", LocalTime.class), CongestionSource.valueOf(rs.getString("source")),
                    rs.getBigDecimal("congestion_pct"), rs.getString("congestion_level"), rs.getString("congestion_level_label"));
            result.computeIfAbsent(key, k -> new HashMap<>()).merge(stat.slot(), stat,
                    (kept, other) -> other.source().ordinal() < kept.source().ordinal() ? other : kept);
        });
        return result;
    }

    /** stations.is_supported, or empty when the station does not exist. */
    public Optional<Boolean> stationSupported(long stationId) {
        return jdbc.queryForList("select is_supported from stations where id = :id", Map.of("id", stationId), Boolean.class)
                .stream().findFirst();
    }

    /** The graph node (station_lines) of a station on a line. */
    public Optional<Node> node(long stationId, long lineId) {
        return jdbc.query("select id from station_lines where station_id = :station and line_id = :line",
                Map.of("station", stationId, "line", lineId), (rs, n) -> new Node(rs.getLong("id"), stationId, lineId))
                .stream().findFirst();
    }
}
