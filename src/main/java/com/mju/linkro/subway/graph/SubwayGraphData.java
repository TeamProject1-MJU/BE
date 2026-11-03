package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.StepType;
import java.util.List;

/** Raw rows of the four subway tables. Kept apart from the DB so graphs can be built from hand-made data in tests. */
public record SubwayGraphData(List<Line> lines, List<Station> stations, List<Node> nodes, List<Edge> edges) {

    public SubwayGraphData {
        lines = List.copyOf(lines);
        stations = List.copyOf(stations);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public record Line(long id, String code, String name, String color, int sortOrder) {}

    public record Station(long id, String name, double latitude, double longitude, boolean supported) {}

    /** station_lines row: one graph node per (station, line). */
    public record Node(long id, long stationId, long lineId, String stationCode, int seq, String branchCode) {}

    /** station_connections row. {@code direction} is null for TRANSFER. */
    public record Edge(long id, long fromNodeId, long toNodeId, StepType type, DirectionCode direction, int seconds, int meters) {}
}
