package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Read-only JDBC load of the V10 tables, done once at startup. */
public class SubwayGraphLoader {
    private final NamedParameterJdbcTemplate jdbc;
    public SubwayGraphLoader(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public SubwayGraphData load() {
        var lines = jdbc.query("select id, line_code, name, color, sort_order from subway_lines order by sort_order, id", Map.of(),
                (rs, n) -> new Line(rs.getLong("id"), rs.getString("line_code"), rs.getString("name"), rs.getString("color"),
                        rs.getInt("sort_order")));
        var stations = jdbc.query("select id, name, latitude, longitude, is_supported from stations order by id", Map.of(),
                (rs, n) -> new Station(rs.getLong("id"), rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getBoolean("is_supported")));
        var nodes = jdbc.query("select id, station_id, line_id, station_code, seq, branch_code from station_lines order by id", Map.of(),
                (rs, n) -> new Node(rs.getLong("id"), rs.getLong("station_id"), rs.getLong("line_id"), rs.getString("station_code"),
                        rs.getInt("seq"), rs.getString("branch_code")));
        var edges = jdbc.query("""
                select id, from_station_line_id, to_station_line_id, connection_type, direction, travel_seconds, distance_m
                from station_connections order by id
                """, Map.of(), (rs, n) -> new Edge(rs.getLong("id"), rs.getLong("from_station_line_id"),
                rs.getLong("to_station_line_id"), StepType.valueOf(rs.getString("connection_type")),
                rs.getString("direction") == null ? null : DirectionCode.valueOf(rs.getString("direction")),
                rs.getInt("travel_seconds"), rs.getInt("distance_m")));
        return new SubwayGraphData(lines, stations, nodes, edges);
    }
}
