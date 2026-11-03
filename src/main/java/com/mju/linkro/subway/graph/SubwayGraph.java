package com.mju.linkro.subway.graph;

import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Immutable in-memory graph: nodes are station_lines of supported stations, edges are station_connections.
 * Stations with {@code is_supported = false} get no nodes, and edges touching them are dropped.
 */
@Slf4j
public final class SubwayGraph {
    private final Map<Long, Line> lines;
    private final Map<Long, Station> stations;
    private final Map<Long, Node> nodes;
    private final Map<Long, List<Edge>> outgoing;
    private final Map<Long, List<Node>> nodesByStation;
    /** Junction node id -> neighbour node id -> arm of that neighbour. */
    private final Map<Long, Map<Long, String>> junctionArms;
    private final Map<Long, BranchRules.Junction> junctions;
    private final int rideCount;
    private final int transferCount;

    private SubwayGraph(SubwayGraphData data) {
        lines = index(data.lines(), Line::id);
        stations = index(data.stations(), Station::id);
        Map<Long, Node> builtNodes = new LinkedHashMap<>();
        for (Node node : data.nodes()) {
            Station station = stations.get(node.stationId());
            if (station != null && station.supported() && lines.containsKey(node.lineId())) {
                builtNodes.put(node.id(), node);
            }
        }
        nodes = Collections.unmodifiableMap(builtNodes);

        Map<Long, List<Edge>> out = new HashMap<>();
        int rides = 0;
        int transfers = 0;
        for (Edge edge : data.edges()) {
            Node from = nodes.get(edge.fromNodeId());
            Node to = nodes.get(edge.toNodeId());
            if (from == null || to == null) {
                continue;
            }
            warnIfInconsistent(edge, from, to);
            out.computeIfAbsent(from.id(), id -> new ArrayList<>()).add(edge);
            if (edge.type() == StepType.RIDE) rides++; else transfers++;
        }
        out.values().forEach(list -> list.sort(Comparator.comparingLong(Edge::toNodeId).thenComparingLong(Edge::id)));
        outgoing = freeze(out);
        rideCount = rides;
        transferCount = transfers;

        Map<Long, List<Node>> byStation = new HashMap<>();
        nodes.values().forEach(node -> byStation.computeIfAbsent(node.stationId(), id -> new ArrayList<>()).add(node));
        byStation.values().forEach(list -> list.sort(Comparator.comparingInt((Node node) -> lines.get(node.lineId()).sortOrder())
                .thenComparingLong(Node::id)));
        nodesByStation = freeze(byStation);

        Map<Long, Map<Long, String>> arms = new HashMap<>();
        Map<Long, BranchRules.Junction> junctionKeys = new HashMap<>();
        for (Node node : nodes.values()) {
            Map<Long, String> nodeArms = armsOf(node);
            if (nodeArms != null) {
                arms.put(node.id(), Collections.unmodifiableMap(nodeArms));
                var key = new BranchRules.Junction(stations.get(node.stationId()).name(), lines.get(node.lineId()).code());
                junctionKeys.put(node.id(), key);
                if (!BranchRules.known(key)) {
                    log.warn("Subway junction {} line {} is not in the branch rule table; treated as through running", key.stationName(), key.lineCode());
                }
            }
        }
        junctionArms = Collections.unmodifiableMap(arms);
        junctions = Collections.unmodifiableMap(junctionKeys);
    }

    /** Builds the graph; fails when it has no nodes or no edges so a misconfigured graph mode cannot start. */
    public static SubwayGraph of(SubwayGraphData data) {
        SubwayGraph graph = new SubwayGraph(data);
        if (graph.nodes.isEmpty() || graph.rideCount + graph.transferCount == 0) {
            throw new IllegalStateException("Subway graph is empty (" + graph.nodes.size() + " nodes, "
                    + (graph.rideCount + graph.transferCount) + " edges). Check the V10/V11 subway data before using linkro.subway.module=graph.");
        }
        return graph;
    }

    /**
     * A junction is a MAIN node with a RIDE neighbour on another branch of the same line. Its arms are the
     * neighbour's branch code, or MAIN-/MAIN+ by the neighbour's seq.
     */
    private Map<Long, String> armsOf(Node node) {
        if (!"MAIN".equals(node.branchCode())) {
            return null;
        }
        Map<Long, String> arms = new TreeMap<>();
        boolean branchNeighbour = false;
        for (Node neighbour : rideNeighbours(node)) {
            if (!"MAIN".equals(neighbour.branchCode())) {
                arms.put(neighbour.id(), neighbour.branchCode());
                branchNeighbour = true;
            } else {
                arms.put(neighbour.id(), neighbour.seq() < node.seq() ? BranchRules.MAIN_LOWER : BranchRules.MAIN_HIGHER);
            }
        }
        return branchNeighbour ? arms : null;
    }

    private List<Node> rideNeighbours(Node node) {
        Set<Long> ids = new TreeSet<>();
        outgoing.getOrDefault(node.id(), List.of()).stream().filter(edge -> edge.type() == StepType.RIDE)
                .forEach(edge -> ids.add(edge.toNodeId()));
        outgoing.forEach((from, edges) -> edges.stream()
                .filter(edge -> edge.type() == StepType.RIDE && edge.toNodeId() == node.id()).forEach(edge -> ids.add(from)));
        return ids.stream().map(nodes::get).filter(neighbour -> neighbour.lineId() == node.lineId()).toList();
    }

    private void warnIfInconsistent(Edge edge, Node from, Node to) {
        if (edge.type() == StepType.RIDE && from.lineId() != to.lineId()) {
            log.warn("Subway RIDE edge {} joins different lines ({} -> {})", edge.id(), from.lineId(), to.lineId());
        }
        if (edge.type() == StepType.TRANSFER && (from.stationId() != to.stationId() || from.lineId() == to.lineId())) {
            log.warn("Subway TRANSFER edge {} must join one station on different lines (station {} -> {}, line {} -> {})",
                    edge.id(), from.stationId(), to.stationId(), from.lineId(), to.lineId());
        }
    }

    public int nodeCount() { return nodes.size(); }
    public int rideCount() { return rideCount; }
    public int transferCount() { return transferCount; }
    public int stationCount() { return stations.size(); }

    Optional<Station> station(long stationId) { return Optional.ofNullable(stations.get(stationId)); }
    Node node(long nodeId) { return nodes.get(nodeId); }
    Line line(long lineId) { return lines.get(lineId); }
    List<Node> nodesOf(long stationId) { return nodesByStation.getOrDefault(stationId, List.of()); }
    List<Edge> outgoing(long nodeId) { return outgoing.getOrDefault(nodeId, List.of()); }
    Collection<Station> stations() { return stations.values(); }

    /** Arm of {@code neighbourId} at junction {@code nodeId}, or null when the node is not a junction. */
    String armAt(long nodeId, long neighbourId) {
        Map<Long, String> arms = junctionArms.get(nodeId);
        return arms == null ? null : arms.get(neighbourId);
    }

    BranchRules.Junction junction(long nodeId) { return junctions.get(nodeId); }
    /** Junction node id -> its junction key. */
    Map<Long, BranchRules.Junction> junctionNodes() { return junctions; }
    /** Distinct arms around a junction node (empty for other nodes). */
    Set<String> armsAt(long nodeId) { return new TreeSet<>(junctionArms.getOrDefault(nodeId, Map.of()).values()); }

    private static <T> Map<Long, T> index(List<T> rows, java.util.function.ToLongFunction<T> id) {
        Map<Long, T> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(id.applyAsLong(row), row));
        return Collections.unmodifiableMap(map);
    }

    private static <T> Map<Long, List<T>> freeze(Map<Long, List<T>> map) {
        Map<Long, List<T>> copy = new HashMap<>();
        map.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }
}
