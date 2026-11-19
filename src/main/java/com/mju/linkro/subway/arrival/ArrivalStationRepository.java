package com.mju.linkro.subway.arrival;

import com.mju.linkro.subway.api.DirectionCode;
import java.util.List;
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

    /** One station_lines row of a line: the node, its station name and branch_code. */
    public record LineNode(long id, String stationName, String branchCode) {}

    /** One RIDE edge of a line. */
    public record LineEdge(long fromNodeId, long toNodeId, DirectionCode direction) {}

    /** Every node and RIDE edge of one line (#174), or empty for an unknown line. */
    public record LineGraph(String lineCode, List<LineNode> nodes, List<LineEdge> edges) {}

    public Optional<LineGraph> lineGraph(long lineId) {
        Optional<String> code = jdbc.queryForList("select line_code from subway_lines where id = :line", Map.of("line", lineId), String.class)
                .stream().findFirst();
        if (code.isEmpty()) return Optional.empty();
        List<LineNode> nodes = jdbc.query("""
                select sl.id, s.name, sl.branch_code from station_lines sl join stations s on s.id = sl.station_id
                where sl.line_id = :line
                """, Map.of("line", lineId), (rs, n) -> new LineNode(rs.getLong("id"), rs.getString("name"), rs.getString("branch_code")));
        List<LineEdge> edges = jdbc.query("""
                select c.from_station_line_id, c.to_station_line_id, c.direction from station_connections c
                join station_lines f on f.id = c.from_station_line_id
                where c.connection_type = 'RIDE' and f.line_id = :line
                """, Map.of("line", lineId), (rs, n) -> new LineEdge(rs.getLong("from_station_line_id"), rs.getLong("to_station_line_id"),
                DirectionCode.valueOf(rs.getString("direction"))));
        return Optional.of(new LineGraph(code.get(), nodes, edges));
    }

    /** Direction of the RIDE edge from one node to the next on the same line, if they are neighbours. */
    public Optional<DirectionCode> rideDirection(long fromNodeId, long toNodeId) {
        return jdbc.queryForList("""
                select direction from station_connections
                where connection_type = 'RIDE' and from_station_line_id = :from and to_station_line_id = :to
                """, Map.of("from", fromNodeId, "to", toNodeId), String.class).stream().findFirst().map(DirectionCode::valueOf);
    }
}
