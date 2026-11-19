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
    /** Nodes touching a LOOP edge (6호선 응암 loop): no destination rule applies there. */
    private final Set<Long> loopNodes = new HashSet<>();

    DestinationFilter(LineGraph graph) {
        Set<String> repeated = new HashSet<>();
        for (LineNode node : graph.nodes()) {
            nodes.put(node.id(), node);
            String name = StationNames.normalize(node.stationName());
            if (byName.put(name, node.id()) != null) repeated.add(name);
        }
        repeated.forEach(byName::remove);
        for (LineEdge edge : graph.edges()) {
            out.computeIfAbsent(edge.fromNodeId(), k -> new ArrayList<>()).add(edge);
            if (edge.direction() == DirectionCode.LOOP) {
                loopNodes.add(edge.fromNodeId());
                loopNodes.add(edge.toNodeId());
            }
        }
    }

    /** The branch rule alone: see {@link #rules}. */
    Optional<Predicate<SeoulArrivalClient.Arrival>> branch(long board, long next, long alight) {
        return rules(board, next, alight, true, false);
    }

    /**
     * The rules from {@code board} (riding to {@code next}) to {@code alight}, or empty when they cannot be applied: no
     * RIDE edge board -> next, a circular direction, a loop station, or an alighting station that is not ahead.
     *
     * @param branchRule drop trains bound for the other branch past a junction before {@code alight}
     * @param shortTurn  drop trains ending at the boarding station or before {@code alight}
     */
    Optional<Predicate<SeoulArrivalClient.Arrival>> rules(long board, long next, long alight, boolean branchRule, boolean shortTurn) {
        Optional<DirectionCode> riding = direction(board, next);
        if (riding.isEmpty() || !linear(riding.get()) || loopNodes.contains(board) || loopNodes.contains(alight)) return Optional.empty();
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
