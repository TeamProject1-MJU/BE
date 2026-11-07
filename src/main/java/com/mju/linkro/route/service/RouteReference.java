package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.congestion.load.StationNames;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.graph.SubwayGraphLoader;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Subway reference data for mapping Kakao routes: lines, stations, nodes by (line code, normalized name) and RIDE
 * edges by (from node, to node). Read once on first use; the tables only change by migration.
 */
@Component
public class RouteReference {
    private final NamedParameterJdbcTemplate jdbc;
    private volatile Index index;

    public RouteReference(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Index index() {
        Index loaded = index;
        if (loaded == null) {
            synchronized (this) {
                if (index == null) index = Index.of(new SubwayGraphLoader(jdbc).load());
                loaded = index;
            }
        }
        return loaded;
    }

    public record Index(Map<Long, Line> lines, Map<String, Line> linesByCode, Map<Long, Station> stations,
                        Map<Long, Node> nodes, Map<String, Node> nodesByName, Map<String, Edge> rides) {

        public static Index of(SubwayGraphData data) {
            Map<Long, Line> lines = new HashMap<>();
            Map<String, Line> byCode = new HashMap<>();
            data.lines().forEach(line -> { lines.put(line.id(), line); byCode.put(line.code(), line); });
            Map<Long, Station> stations = new HashMap<>();
            data.stations().forEach(station -> stations.put(station.id(), station));
            Map<Long, Node> nodes = new HashMap<>();
            Map<String, Node> byName = new HashMap<>();
            Set<String> ambiguous = new HashSet<>();
            for (Node node : data.nodes()) {
                nodes.put(node.id(), node);
                String key = key(lines.get(node.lineId()).code(), stations.get(node.stationId()).name());
                if (byName.putIfAbsent(key, node) != null) ambiguous.add(key);
            }
            // Two nodes with the same normalized name on one line cannot be told apart: match neither.
            ambiguous.forEach(byName::remove);
            Map<String, Edge> rides = new HashMap<>();
            data.edges().stream().filter(edge -> edge.type() == StepType.RIDE)
                    .forEach(edge -> rides.put(edge.fromNodeId() + ">" + edge.toNodeId(), edge));
            return new Index(Map.copyOf(lines), Map.copyOf(byCode), Map.copyOf(stations), Map.copyOf(nodes),
                    Map.copyOf(byName), Map.copyOf(rides));
        }

        /** Node of a station name on a line code, both sides normalized alike (괄호·"역" 제거, 이수 별칭). */
        public Optional<Node> node(String lineCode, String stationName) {
            return Optional.ofNullable(nodesByName.get(key(lineCode, stationName)));
        }

        public Optional<Edge> ride(long fromNodeId, long toNodeId) {
            return Optional.ofNullable(rides.get(fromNodeId + ">" + toNodeId));
        }

        private static String key(String lineCode, String stationName) {
            return lineCode + "|" + StationNames.normalize(stationName);
        }
    }
}
