package com.mju.linkro.subway.arrival;

import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.LineEdge;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.LineGraph;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.LineNode;
import com.mju.linkro.subway.congestion.load.StationNames;
import java.util.*;
import java.util.function.Predicate;

/**
 * Filters by a train's destination (bstatnNm) on one line's seed graph (#174, docs/arrivals-86.md). Positions are found by
 * following the RIDE edges of the riding direction, never by seq numbers. Every unclear case keeps the train (fail-open):
 * a destination that is no station of the line, an alighting station that is not ahead, a direction that cannot be told.
 */
final class DestinationFilter {
    private final Map<Long, List<LineEdge>> out = new HashMap<>();
    private final Map<Long, LineNode> nodes = new HashMap<>();
    /** Normalized station name -> node; names used by more than one node are left out (ambiguous, kept). */
    private final Map<String, Long> byName = new HashMap<>();
    /** On a circular line, the node whose destination always stays ({@link SeoulSubwayIds#RING_CIRCUIT_STATIONS}), or null. */
    private final Long circuit;

    DestinationFilter(LineGraph graph) {
        Set<String> repeated = new HashSet<>();
        for (LineNode node : graph.nodes()) {
            nodes.put(node.id(), node);
            String name = StationNames.normalize(node.stationName());
            if (byName.put(name, node.id()) != null) repeated.add(name);
        }
        repeated.forEach(byName::remove);
        String circuitName = SeoulSubwayIds.RING_CIRCUIT_STATIONS.get(graph.lineCode());
        circuit = circuitName == null ? null : byName.get(StationNames.normalize(circuitName));
        for (LineEdge edge : graph.edges()) {
            out.computeIfAbsent(edge.fromNodeId(), k -> new ArrayList<>()).add(edge);
        }
    }

    /** The branch rule alone: see {@link #rules}. */
    Optional<Predicate<SeoulArrivalClient.Arrival>> branch(long board, long next, long alight) {
        return rules(board, next, alight, true, false);
    }

    /**
     * The rules from {@code board} (riding to {@code next}) to {@code alight}, or empty when they cannot be applied: no
     * RIDE edge board -> next, a circular direction, boarding into the loop at its entry, or an alighting station that is
     * not ahead.
     *
     * <p>Riding a LOOP edge (6호선 응암 loop, #177) the way ahead is the loop up to the station where trains leave it
     * (응암, the one with another way out), then that way out (DOWN toward 새절): every train in the loop goes round once
     * and leaves there. Boarding at that station into the loop gets no rule (its direction filter, #162/#164, stays);
     * boarding there on the way out (DOWN) is an ordinary linear ride.
     *
     * @param branchRule drop trains bound for the other branch past a junction before {@code alight}
     * @param shortTurn  drop trains ending at the boarding station or before {@code alight}
     */
    Optional<Predicate<SeoulArrivalClient.Arrival>> rules(long board, long next, long alight, boolean branchRule, boolean shortTurn) {
        Optional<DirectionCode> riding = direction(board, next);
        if (riding.isEmpty()) return Optional.empty();
        if (riding.get() == DirectionCode.LOOP) {
            if (!shortTurn || hasWayOut(board)) return Optional.empty();
            return inOrder(board, loopPath(next), alight, null);
        }
        if (isRing(riding.get())) {
            // 2호선 (#177): once round in the riding direction only (INNER or OUTER, not the branch edges); the circuit
            // station's destination always stays. Without a circuit station the line is not handled as a ring.
            if (!shortTurn || circuit == null) return Optional.empty();
            return inOrder(board, ringPath(board, next, riding.get()), alight, circuit);
        }
        if (!linear(riding.get())) return Optional.empty();
        DirectionCode d = riding.get();
        Set<Long> ahead = ahead(next, d);
        if (!ahead.contains(alight)) return Optional.empty();
        Long junction = branchRule ? junction(board, ahead, alight, d) : null;
        Set<Long> pastJunction = junction == null ? Set.of() : reach(junction, d);
        String branch = nodes.get(alight).branchCode();
        return Optional.of(arrival -> {
            Long destination = destination(arrival);
            if (destination == null) return true;
            if (pastJunction.contains(destination) && !branch.equals(nodes.get(destination).branchCode())) return false;
            if (shortTurn && (destination == board || (ahead.contains(destination) && reach(destination, d).contains(alight)))) return false;
            return true;
        });
    }

    /**
     * Short-turn rule along an ordered way ahead: trains ending at the boarding station or at a station of the way before
     * {@code alight} are dropped; destinations not on the way are kept. Empty when {@code alight} is not on it.
     */
    private Optional<Predicate<SeoulArrivalClient.Arrival>> inOrder(long board, List<Long> path, long alight, Long alwaysKept) {
        int alightAt = path.indexOf(alight);
        if (alightAt < 0) return Optional.empty();
        Map<Long, Integer> position = new HashMap<>();
        for (int i = 0; i < path.size(); i++) position.putIfAbsent(path.get(i), i);
        return Optional.of(arrival -> {
            Long destination = destination(arrival);
            if (destination == null || destination.equals(alwaysKept)) return true;
            if (destination == board) return false;
            Integer at = position.get(destination);
            return at == null || at >= alightAt;
        });
    }

    private static boolean isRing(DirectionCode d) {
        return d == DirectionCode.INNER || d == DirectionCode.OUTER;
    }

    /** From {@code next} once round in direction d, following the single edge of that direction, until back at board. */
    private List<Long> ringPath(long board, long next, DirectionCode d) {
        List<Long> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long current = next;
        while (current != board && seen.add(current)) {
            path.add(current);
            List<LineEdge> edges = out.getOrDefault(current, List.of()).stream().filter(edge -> edge.direction() == d).toList();
            if (edges.size() != 1) break;
            current = edges.get(0).toNodeId();
        }
        return path;
    }

    /** True when the node has a RIDE edge that is not LOOP: the station where trains leave the loop (응암). */
    private boolean hasWayOut(long node) {
        return out.getOrDefault(node, List.of()).stream().anyMatch(edge -> edge.direction() != DirectionCode.LOOP);
    }

    /**
     * From {@code next} along the LOOP edges to the station with a way out, then along that way out while it has exactly
     * one edge in its direction. Stops at a station already on the way.
     */
    private List<Long> loopPath(long next) {
        List<Long> path = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        long current = next;
        DirectionCode d = DirectionCode.LOOP;
        while (seen.add(current)) {
            path.add(current);
            if (d == DirectionCode.LOOP && hasWayOut(current)) {
                List<DirectionCode> ways = out.get(current).stream().map(LineEdge::direction).filter(dir -> dir != DirectionCode.LOOP).distinct().toList();
                if (ways.size() != 1) break;
                d = ways.get(0);
            }
            DirectionCode want = d;
            List<LineEdge> edges = out.getOrDefault(current, List.of()).stream().filter(edge -> edge.direction() == want).toList();
            if (edges.size() != 1) break;
            current = edges.get(0).toNodeId();
        }
        return path;
    }

    private Long destination(SeoulArrivalClient.Arrival arrival) {
        return arrival.bstatnNm() == null || arrival.bstatnNm().isBlank() ? null : byName.get(StationNames.normalize(arrival.bstatnNm()));
    }

    /** The first node from board on that splits in direction d and has the alighting station past it, or null. */
    private Long junction(long board, Set<Long> ahead, long alight, DirectionCode d) {
        List<Long> path = new ArrayList<>();
        path.add(board);
        path.addAll(ahead);
        for (long node : path) {
            if (node == alight) return null;
            long split = out.getOrDefault(node, List.of()).stream().filter(edge -> edge.direction() == d).count();
            if (split > 1 && reach(node, d).contains(alight)) return node;
        }
        return null;
    }

    private Optional<DirectionCode> direction(long from, long to) {
        return out.getOrDefault(from, List.of()).stream().filter(edge -> edge.toNodeId() == to).map(LineEdge::direction).findFirst();
    }

    private static boolean linear(DirectionCode d) {
        return d == DirectionCode.UP || d == DirectionCode.DOWN;
    }

    /** {@code next} and every node reachable from it in direction d, in visiting order. */
    private Set<Long> ahead(long next, DirectionCode d) {
        Set<Long> ahead = new LinkedHashSet<>();
        ahead.add(next);
        ahead.addAll(reach(next, d));
        return ahead;
    }

    /** Nodes reachable from {@code start} (excluded) by RIDE edges in direction d. */
    private Set<Long> reach(long start, DirectionCode d) {
        Set<Long> seen = new LinkedHashSet<>();
        Deque<Long> stack = new ArrayDeque<>(List.of(start));
        while (!stack.isEmpty()) {
            for (LineEdge edge : out.getOrDefault(stack.pop(), List.of())) {
                if (edge.direction() == d && edge.toNodeId() != start && seen.add(edge.toNodeId())) stack.push(edge.toNodeId());
            }
        }
        return seen;
    }
}
