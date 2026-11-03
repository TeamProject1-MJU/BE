package com.mju.linkro.subway.graph;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimum-time routes by Dijkstra over (node, arm) states.
 *
 * <p>Cost is the sum of edge travel_seconds plus the configured dwell, transfer wait and branch transfer cost.
 * Every node of the origin starts at 0 and reaching any node of the destination ends the route. Ties prefer fewer
 * transfers, then the lower node id, so the same input always gives the same route. The arm part of the state is
 * the side a junction was entered from, so a train change at a branch junction (#56) can be charged.
 */
@Slf4j
public class DijkstraSubwayRouteFinder implements SubwayRouteFinder {
    private final SubwayGraph graph;
    private final SubwayGraphProperties costs;
    private final Map<BranchRules.Junction, SubwayGraphProperties.BranchTransfer> branchTransfers = new HashMap<>();

    public DijkstraSubwayRouteFinder(SubwayGraph graph, SubwayGraphProperties costs) {
        costs.validate();
        this.graph = graph;
        this.costs = costs;
        costs.getBranchTransfers().forEach(transfer ->
                branchTransfers.put(new BranchRules.Junction(transfer.getStation(), transfer.getLine()), transfer));
        // Warn only where two different arms really need a train change (응암 never does).
        graph.junctionNodes().entrySet().stream()
                .filter(entry -> BranchRules.known(entry.getValue()) && !branchTransfers.containsKey(entry.getValue()))
                .filter(entry -> needsTransfer(entry.getValue(), graph.armsAt(entry.getKey())))
                .map(Map.Entry::getValue).distinct()
                .sorted(Comparator.comparing(BranchRules.Junction::stationName).thenComparing(BranchRules.Junction::lineCode))
                .forEach(junction -> log.warn("No branch transfer time for junction {} line {}; using {} s, 0 m",
                        junction.stationName(), junction.lineCode(), costs.getDefaultBranchTransferSeconds()));
    }

    private static boolean needsTransfer(BranchRules.Junction junction, Set<String> arms) {
        for (String a : arms) {
            for (String b : arms) {
                if (!a.equals(b) && !BranchRules.through(junction, a, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public RoutePath shortestPath(long fromStationId, long toStationId) {
        requireStation(fromStationId);
        requireStation(toStationId);
        return shortestPathsFrom(fromStationId).pathTo(toStationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ROUTE_NOT_FOUND));
    }

    @Override
    public ShortestPathTree shortestPathsFrom(long fromStationId) {
        requireStation(fromStationId);
        return new Tree(fromStationId, search(fromStationId));
    }

    private void requireStation(long stationId) {
        Station station = graph.station(stationId).orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
        if (!station.supported()) {
            throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
        }
    }

    // ---- search ----

    /** Node plus the arm the junction was entered from; arm is null outside junctions and after boarding. */
    private record State(long nodeId, String arm) {}

    /** One move into a state: a RIDE or TRANSFER edge, optionally preceded by a branch transfer at its start node. */
    private record Move(Edge edge, int branchSeconds, int branchMeters, boolean branchTransfer) {}

    private record Label(int cost, int transfers, State previous, Move move) {}

    private static final Comparator<State> STATE_ORDER = Comparator.comparingLong(State::nodeId)
            .thenComparing(State::arm, Comparator.nullsFirst(Comparator.naturalOrder()));

    private record Queued(int cost, int transfers, State state) {}

    private Map<State, Label> search(long originStationId) {
        Map<State, Label> labels = new HashMap<>();
        Set<State> settled = new HashSet<>();
        PriorityQueue<Queued> queue = new PriorityQueue<>(Comparator.comparingInt(Queued::cost)
                .thenComparingInt(Queued::transfers).thenComparing(Queued::state, STATE_ORDER));
        for (Node node : graph.nodesOf(originStationId)) {
            State start = new State(node.id(), null);
            labels.put(start, new Label(0, 0, null, null));
            queue.add(new Queued(0, 0, start));
        }
        while (!queue.isEmpty()) {
            Queued current = queue.poll();
            if (!settled.add(current.state())) {
                continue;
            }
            Label label = labels.get(current.state());
            for (Edge edge : graph.outgoing(current.state().nodeId())) {
                relax(current.state(), label, edge, labels, settled, queue);
            }
        }
        return labels;
    }

    private void relax(State state, Label label, Edge edge, Map<State, Label> labels, Set<State> settled, PriorityQueue<Queued> queue) {
        int cost = label.cost();
        int transfers = label.transfers();
        Move move;
        State next;
        if (edge.type() == StepType.TRANSFER) {
            cost += edge.seconds() + costs.getTransferWaitSeconds();
            transfers++;
            move = new Move(edge, 0, 0, false);
            next = new State(edge.toNodeId(), null);
        } else {
            cost += edge.seconds() + costs.getDwellSeconds();
            move = new Move(edge, 0, 0, false);
            BranchRules.Junction junction = graph.junction(state.nodeId());
            if (state.arm() != null && junction != null
                    && !BranchRules.through(junction, state.arm(), graph.armAt(state.nodeId(), edge.toNodeId()))) {
                var transfer = branchTransfers.get(junction);
                int walk = transfer == null ? costs.getDefaultBranchTransferSeconds() : transfer.getSeconds();
                int meters = transfer == null ? 0 : transfer.getMeters();
                int branchCost = walk + costs.getBranchTransferWaitSeconds();
                cost += branchCost;
                transfers++;
                move = new Move(edge, branchCost, meters, true);
            }
            next = new State(edge.toNodeId(), graph.armAt(edge.toNodeId(), state.nodeId()));
        }
        if (settled.contains(next)) {
            return;
        }
        Label existing = labels.get(next);
        if (existing == null || cost < existing.cost() || (cost == existing.cost() && transfers < existing.transfers())) {
            labels.put(next, new Label(cost, transfers, state, move));
            queue.add(new Queued(cost, transfers, next));
        }
    }

    // ---- tree and route assembly ----

    private final class Tree implements ShortestPathTree {
        private final long originStationId;
        private final Map<State, Label> labels;
        private final Map<Long, State> bestByStation = new HashMap<>();

        Tree(long originStationId, Map<State, Label> labels) {
            this.originStationId = originStationId;
            this.labels = labels;
            Comparator<State> order = Comparator.<State>comparingInt(state -> labels.get(state).cost())
                    .thenComparingInt(state -> labels.get(state).transfers()).thenComparing(STATE_ORDER);
            labels.keySet().forEach(state -> bestByStation.merge(graph.node(state.nodeId()).stationId(), state,
                    (a, b) -> order.compare(a, b) <= 0 ? a : b));
        }

        @Override
        public Set<Long> reachableStationIds() {
            return Collections.unmodifiableSet(new TreeSet<>(bestByStation.keySet()));
        }

        @Override
        public Optional<RoutePath> pathTo(long stationId) {
            State end = bestByStation.get(stationId);
            return end == null ? Optional.empty() : Optional.of(assemble(end));
        }

        private RoutePath assemble(State end) {
            Deque<Move> moves = new ArrayDeque<>();
            State cursor = end;
            while (labels.get(cursor).previous() != null) {
                moves.addFirst(labels.get(cursor).move());
                cursor = labels.get(cursor).previous();
            }
            return new RouteBuilder(graph, graph.node(cursor.nodeId()))
                    .addAll(moves, costs).build(originStationId, graph.node(end.nodeId()).stationId());
        }
    }

    /** Turns moves into steps and stops: consecutive RIDE edges of one line and direction become one step. */
    private static final class RouteBuilder {
        private final SubwayGraph graph;
        private final List<PathStep> steps = new ArrayList<>();
        private final List<PathStop> stops = new ArrayList<>();
        private int offset;
        private int transfers;
        private int rideDistance;
        // Open RIDE step.
        private Node rideFrom;
        private Node rideTo;
        private DirectionCode rideDirection;
        private int rideEdges;
        private int rideSeconds;
        private int rideMeters;

        RouteBuilder(SubwayGraph graph, Node start) {
            this.graph = graph;
            stops.add(stop(start, null, 0));
        }

        RouteBuilder addAll(Collection<Move> moves, SubwayGraphProperties costs) {
            for (Move move : moves) {
                Edge edge = move.edge();
                if (move.branchTransfer()) {
                    Node junction = graph.node(edge.fromNodeId());
                    transfer(junction, junction, junction.lineId(), move.branchSeconds(), move.branchMeters());
                }
                if (edge.type() == StepType.TRANSFER) {
                    Node to = graph.node(edge.toNodeId());
                    transfer(graph.node(edge.fromNodeId()), to, to.lineId(), edge.seconds() + costs.getTransferWaitSeconds(), edge.meters());
                } else {
                    ride(edge, edge.seconds() + costs.getDwellSeconds());
                }
            }
            closeRide();
            return this;
        }

        private void ride(Edge edge, int seconds) {
            Node from = graph.node(edge.fromNodeId());
            Node to = graph.node(edge.toNodeId());
            boolean continues = rideTo != null && rideTo.id() == from.id() && rideTo.lineId() == to.lineId()
                    && rideDirection == edge.direction();
            if (!continues) {
                closeRide();
                rideFrom = from;
                rideDirection = edge.direction();
            }
            // The stop where the train is boarded (or passed) takes the direction of the train leaving it.
            PathStop last = stops.get(stops.size() - 1);
            stops.set(stops.size() - 1, stop(from, edge.direction(), last.offsetSec()));
            offset += seconds;
            stops.add(stop(to, edge.direction(), offset));
            rideTo = to;
            rideEdges++;
            rideSeconds += seconds;
            rideMeters += edge.meters();
        }

        private void closeRide() {
            if (rideFrom == null) {
                return;
            }
            Line line = graph.line(rideFrom.lineId());
            steps.add(new PathStep(StepType.RIDE, line.id(), line.name(), line.color(), rideDirection,
                    rideFrom.stationId(), stationName(rideFrom), rideTo.stationId(), stationName(rideTo),
                    rideEdges, rideSeconds, rideMeters));
            rideDistance += rideMeters;
            rideFrom = null;
            rideTo = null;
            rideDirection = null;
            rideEdges = 0;
            rideSeconds = 0;
            rideMeters = 0;
        }

        /** The boarding stop gets its direction from the next RIDE. */
        private void transfer(Node from, Node to, long lineId, int seconds, int meters) {
            closeRide();
            Line line = graph.line(lineId);
            steps.add(new PathStep(StepType.TRANSFER, line.id(), line.name(), line.color(), null,
                    from.stationId(), stationName(from), to.stationId(), stationName(to), 0, seconds, meters));
            transfers++;
            offset += seconds;
            stops.add(stop(to, null, offset));
        }

        RoutePath build(long fromStationId, long toStationId) {
            return new RoutePath(steps, stops, offset, transfers, rideDistance, fromStationId, toStationId);
        }

        private PathStop stop(Node node, DirectionCode direction, int offsetSec) {
            Station station = graph.station(node.stationId()).orElseThrow();
            return new PathStop(station.id(), station.name(), station.latitude(), station.longitude(), node.id(),
                    node.lineId(), direction, offsetSec);
        }

        private String stationName(Node node) {
            return graph.station(node.stationId()).orElseThrow().name();
        }
    }
}
