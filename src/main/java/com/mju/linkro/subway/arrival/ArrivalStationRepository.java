package com.mju.linkro.subway.arrival;

import com.mju.linkro.subway.api.DirectionCode;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only station, node and RIDE edge lookups for the arrival API. */
@Repository
public class ArrivalStationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ArrivalStationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Station(long id, String name, boolean supported) {}

    /** station_lines row with its line_code. */
    public record Node(long id, String lineCode, String stationCode) {}

    public Optional<Station> station(long stationId) {
        return jdbc.query("select id, name, is_supported from stations where id = :id", Map.of("id", stationId),
                (rs, n) -> new Station(rs.getLong("id"), rs.getString("name"), rs.getBoolean("is_supported"))).stream().findFirst();
    }

    public Optional<Node> node(long stationId, long lineId) {
        return jdbc.query("""
                select sl.id, l.line_code, sl.station_code from station_lines sl join subway_lines l on l.id = sl.line_id
                where sl.station_id = :station and sl.line_id = :line
                """, Map.of("station", stationId, "line", lineId),
                (rs, n) -> new Node(rs.getLong("id"), rs.getString("line_code"), rs.getString("station_code"))).stream().findFirst();
    }

    /** Direction of the RIDE edge from one node to the next on the same line, if they are neighbours. */
    public Optional<DirectionCode> rideDirection(long fromNodeId, long toNodeId) {
        return jdbc.queryForList("""
                select direction from station_connections
                where connection_type = 'RIDE' and from_station_line_id = :from and to_station_line_id = :to
                """, Map.of("from", fromNodeId, "to", toNodeId), String.class).stream().findFirst().map(DirectionCode::valueOf);
    }
}
