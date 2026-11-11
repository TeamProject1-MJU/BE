package com.mju.linkro.subway.graph;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TimetableClock.ServiceTime;
import com.mju.linkro.subway.timetable.TrainTimetable;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Time-dependent routes on the trains of {@link TrainTimetable} (#96).
 *
 * <p>Both searches are Dijkstra over states (node, the edge used at that node). Riding an edge means taking one real
 * train of it: backwards the latest train that reaches the next stop in time ({@code lastDepartureArriving}),
 * forwards the next train ({@code nextDeparture}). The wait is whatever lies between two trains, so staying on one
 * train waits nothing and a change waits for the next train. A TRANSFER edge adds its walking time (station_connections)
 * before that wait; there is no fixed transfer wait and no dwell setting. Changing trains on the same platform is free,
 * except at a branch junction (#56) where {@link BranchRules} needs a change: there the walk of the junction's
 * {@code branch-transfers} setting is added. Times are seconds of the service day ({@link TimetableClock}), so trains
 * after midnight keep counting past 86400.
 *
 * <p>A ride never goes straight back to the station it came from (A -> B, then B -> A): waiting for a train back is
 * never a real route, and it would dodge the junction change walk. Only junction nodes keep one state per edge; at
 * other nodes the best state dominates, because the next train only depends on the time.
 */
public class TimetableRouter implements TimetableRouteFinder {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SubwayGraph graph;
    private final TrainTimetable timetable;
    private final TimetableClock clock;
    private final int accessSeconds;
    private final Duration noServiceWindow;
    private final SubwayGraphProperties costs;
    private final Map<BranchRules.Junction, SubwayGraphProperties.BranchTransfer> branchTransfers = new HashMap<>();
    // Dense indices for the searches: nodes 0..n-1, edges 0..m-1.
    private final Map<Long, Integer> nodeIndex = new HashMap<>();
    private final long[] nodeIds;
    private final boolean[] junctionNode;
    private final Edge[] edges;
    private final int[] edgeFrom;
    private final int[] edgeTo;
    /** node -> edges ending / starting there. */
    private final int[][] incoming;
    private final int[][] outgoing;
    /** day type ordinal -> edge -> rides, null when no train runs it. */
    private final TrainTimetable.Rides[][] rides;

    /**
     * @param accessSeconds   from leaving to standing on the first platform, added before the first train
     * @param noServiceWindow how long before an appointment some train must arrive for the subway to count as running
     */
    public TimetableRouter(SubwayGraph graph, TrainTimetable timetable, TimetableClock clock, SubwayGraphProperties costs,
                           int accessSeconds, Duration noServiceWindow) {
        if (accessSeconds < 0) throw new IllegalArgumentException("station access seconds must not be negative");
        if (noServiceWindow == null || noServiceWindow.isNegative() || noServiceWindow.isZero()) {
            throw new IllegalArgumentException("linkro.subway.timetable.no-service-window-minutes must be positive");
        }
        this.noServiceWindow = noServiceWindow;
        this.graph = graph;
        this.timetable = timetable;
        this.clock = clock;
        this.costs = costs;
        this.accessSeconds = accessSeconds;
        costs.getBranchTransfers().forEach(transfer ->
                branchTransfers.put(new BranchRules.Junction(transfer.getStation(), transfer.getLine()), transfer));
        List<Node> nodes = new ArrayList<>();
        List<Edge> allEdges = new ArrayList<>();
        for (Station station : graph.stations()) {
            for (Node node : graph.nodesOf(station.id())) {
                nodes.add(node);
                allEdges.addAll(graph.outgoing(node.id()));
            }
        }
        nodes.sort(Comparator.comparingLong(Node::id));
        allEdges.sort(Comparator.comparingLong(Edge::fromNodeId).thenComparingLong(Edge::id));
        nodeIds = new long[nodes.size()];
        junctionNode = new boolean[nodes.size()];
        for (int n = 0; n < nodes.size(); n++) {
            nodeIds[n] = nodes.get(n).id();
            nodeIndex.put(nodeIds[n], n);
            junctionNode[n] = graph.junction(nodeIds[n]) != null;
        }
        edges = allEdges.toArray(new Edge[0]);
        edgeFrom = new int[edges.length];
        edgeTo = new int[edges.length];
        List<List<Integer>> in = new ArrayList<>();
        List<List<Integer>> out = new ArrayList<>();
        for (int n = 0; n < nodeIds.length; n++) {
            in.add(new ArrayList<>());
            out.add(new ArrayList<>());
        }
        for (int e = 0; e < edges.length; e++) {
            edgeFrom[e] = nodeIndex.get(edges[e].fromNodeId());
            edgeTo[e] = nodeIndex.get(edges[e].toNodeId());
            in.get(edgeTo[e]).add(e);
            out.get(edgeFrom[e]).add(e);
        }
        incoming = in.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        outgoing = out.stream().map(list -> list.stream().mapToInt(Integer::intValue).toArray()).toArray(int[][]::new);
        rides = new TrainTimetable.Rides[DayType.values().length][edges.length];
        for (DayType day : DayType.values()) {
            for (int e = 0; e < edges.length; e++) {
                if (edges[e].type() == StepType.RIDE) rides[day.ordinal()][e] = timetable.rides(edges[e].id(), day);
            }
        }
    }

    @Override
    public boolean covers(Instant at) {
        return timetable.hasTrains(serviceTime(at).dayType());
    }

    @Override
    public boolean noTrainArrivesBefore(Instant at) {
        Instant from = at.minus(noServiceWindow);
        LocalDate serviceDate = serviceTime(at).serviceDate();
        for (LocalDate date : List.of(serviceDate.minusDays(1), serviceDate)) {
            DayType day = clock.dayTypeOf(date);
            OptionalInt first = timetable.firstArrivalSec(day);
            OptionalInt last = timetable.lastArrivalSec(day);
            if (first.isEmpty()) continue;
            Instant midnight = date.atStartOfDay(ZONE).toInstant();
            Instant start = midnight.plusSeconds(first.getAsInt());
            Instant end = midnight.plusSeconds(last.getAsInt());
            if (!start.isAfter(at) && !end.isBefore(from)) return false;
        }
        return true;
    }

    @Override
    public Set<Long> stationIds() {
        Set<Long> ids = new TreeSet<>();
        graph.stations().stream().filter(Station::supported).filter(station -> !graph.nodesOf(station.id()).isEmpty())
                .forEach(station -> ids.add(station.id()));
        return ids;
    }

    @Override
    public void requireStation(long stationId) {
        Station station = graph.station(stationId).orElseThrow(() -> new BusinessException(ErrorCode.STATION_NOT_FOUND));
        if (!station.supported()) throw new BusinessException(ErrorCode.STATION_UNSUPPORTED);
    }

    // ---- states ----

    /**
     * A state of either search. {@code edge} is the move at this node: backwards the edge taken from it (null at the
     * destination), forwards the edge that reached it (null at the origin). {@code ride} is the train on a RIDE edge.
     * {@code link} is the neighbouring state along the route (towards the destination backwards, the origin forwards).
     * {@code node} and {@code key} are dense indices: the key is the edge, or (edges + node) when there is none.
     */
    private record Label(int time, int transfers, int node, int key, Edge edge, Departure ride, Label link) {
        boolean walked() { return edge != null && edge.type() == StepType.TRANSFER; }
    }

    private static final Comparator<Label> LATEST_FIRST = (a, b) -> {
        if (a.time() != b.time()) return Integer.compare(b.time(), a.time());
        if (a.transfers() != b.transfers()) return Integer.compare(a.transfers(), b.transfers());
        if (a.node() != b.node()) return Integer.compare(a.node(), b.node());
        return Integer.compare(a.key(), b.key());
    };

    private static final Comparator<Label> EARLIEST_FIRST = (a, b) -> {
        if (a.time() != b.time()) return Integer.compare(a.time(), b.time());
        if (a.transfers() != b.transfers()) return Integer.compare(a.transfers(), b.transfers());
        if (a.node() != b.node()) return Integer.compare(a.node(), b.node());
        return Integer.compare(a.key(), b.key());
    };

    /** Walk added when changing trains at junction {@code node} coming from {@code previous} and going to {@code next}. */
    private int changeSeconds(long previous, long node, long next) {
        BranchRules.Junction junction = graph.junction(node);
        if (junction == null || BranchRules.through(junction, graph.armAt(node, previous), graph.armAt(node, next))) return 0;
        var transfer = branchTransfers.get(junction);
        return transfer == null ? costs.getDefaultBranchTransferSeconds() : transfer.getSeconds();
    }

    private int changeMeters(long node) {
        var transfer = branchTransfers.get(graph.junction(node));
        return transfer == null ? 0 : transfer.getMeters();
    }

    private ServiceTime serviceTime(Instant at) {
        return clock.serviceTimeOf(LocalDateTime.ofInstant(at, ZONE));
    }

    // ---- backward: latest departures ----

    @Override
    public Map<Long, TimedRoute> latestDepartures(long destinationStationId, Instant arriveBy, Set<Long> originStationIds,
                                                  int maxTravelSeconds) {
        requireStation(destinationStationId);
        ServiceTime service = serviceTime(arriveBy);
        DayType day = service.dayType();
        TrainTimetable.Rides[] dayRides = rides[day.ordinal()];
        int deadline = service.secondOfServiceDay();
        // The member leaves at departure − access: anything earlier than this floor is over the limit.
        int floor = maxTravelSeconds > 0 ? deadline - maxTravelSeconds + accessSeconds : Integer.MIN_VALUE;
        long[] originOf = new long[nodeIds.length];
        Arrays.fill(originOf, -1);
        for (long origin : originStationIds) {
            requireStation(origin);
            if (origin != destinationStationId) graph.nodesOf(origin).forEach(node -> originOf[nodeIndex.get(node.id())] = origin);
        }
        long wanted = originStationIds.stream().filter(id -> id != destinationStationId).distinct().count();
        Map<Long, Label> resolved = new HashMap<>();
        PriorityQueue<Label> queue = new PriorityQueue<>(LATEST_FIRST);
        Label[] best = new Label[edges.length + nodeIds.length];
        for (Node node : graph.nodesOf(destinationStationId)) {
            int n = nodeIndex.get(node.id());
            Label start = new Label(deadline, 0, n, edges.length + n, null, null, null);
            best[start.key()] = start;
            queue.add(start);
        }
        boolean[] settledAny = new boolean[nodeIds.length];
        boolean[] settledBoarding = new boolean[nodeIds.length];
        int[] skipped = new int[nodeIds.length];
        Arrays.fill(skipped, -1);
        while (!queue.isEmpty() && resolved.size() < wanted) {
            Label state = queue.poll();
            if (best[state.key()] != state) continue;
            int node = state.node();
            boolean junction = junctionNode[node];
            int uturn = state.ride() == null ? -1 : edgeTo[state.key()];
            int onlyFrom = -1;
            boolean relaxRides = junction || !settledAny[node];
            if (!relaxRides && skipped[node] >= 0 && skipped[node] != uturn) {
                // The best state here goes back to that neighbour; this one gives it a way forward.
                relaxRides = true;
                onlyFrom = skipped[node];
                skipped[node] = -1;
            }
            boolean relaxWalks = !state.walked() && state.edge() != null && !settledBoarding[node];
            if (!relaxRides && !relaxWalks) continue;
            boolean first = !settledAny[node];
            settledAny[node] = true;
            if (!state.walked()) settledBoarding[node] = true;
            if (state.ride() != null && originOf[node] >= 0) resolved.putIfAbsent(originOf[node], state);
            for (int e : incoming[node]) {
                Edge edge = edges[e];
                int from = edgeFrom[e];
                if (edge.type() == StepType.RIDE) {
                    if (!relaxRides || (onlyFrom >= 0 && from != onlyFrom)) continue;
                    if (from == uturn) {
                        if (first && !junction) skipped[node] = from;
                        continue;
                    }
                    TrainTimetable.Rides edgeRides = dayRides[e];
                    if (edgeRides == null) continue;
                    int change = state.ride() == null ? 0 : changeSeconds(nodeIds[from], nodeIds[node], state.edge().toNodeId());
                    int ride = edgeRides.lastArriving(state.time() - change);
                    if (ride < 0 || edgeRides.departSec(ride) < floor) continue;
                    offer(best, queue, edgeRides.departSec(ride), state.transfers() + (change > 0 ? 1 : 0), from, e, edge,
                            edgeRides, ride, state, true);
                } else if (relaxWalks && state.ride() != null) {
                    int time = state.time() - edge.seconds();
                    if (time >= floor) offer(best, queue, time, state.transfers() + 1, from, e, edge, null, -1, state, true);
                }
            }
        }
        Map<Long, TimedRoute> routes = new HashMap<>();
        resolved.forEach((origin, state) -> {
            List<Label> moves = new ArrayList<>();
            for (Label cursor = state; cursor.edge() != null; cursor = cursor.link()) moves.add(cursor);
            TimedRoute latest = build(stayOnBoard(moves, day), service.serviceDate(), origin, destinationStationId);
            // The backward search fixes when to leave; from then on the member takes the earliest way there
            // (장암 -> 모란: an earlier 5호선 and 8호선 connection). Only when it needs no more transfers: one minute is not
            // worth two extra changes (강남 -> 홍대입구 at 22:00 stays on 2호선).
            TimedRoute replayed = forward(origin, latest.leaveAt(), destinationStationId).routeTo(destinationStationId).orElse(latest);
            boolean better = replayed.arriveAt().isBefore(latest.arriveAt())
                    && replayed.path().transferCount() <= latest.path().transferCount();
            routes.put(origin, better ? replayed : latest);
        });
        return routes;
    }

    /** Keeps the better label per key: later (backwards) or earlier (forwards) time, then fewer transfers. */
    private static void offer(Label[] best, PriorityQueue<Label> queue, int time, int transfers, int node, int key, Edge edge,
                              TrainTimetable.Rides edgeRides, int ride, Label link, boolean later) {
        Label current = best[key];
        boolean better = current == null || (later ? time > current.time() : time < current.time())
                || (time == current.time() && transfers < current.transfers());
        if (better) {
            Label label = new Label(time, transfers, node, key, edge, edgeRides == null ? null : edgeRides.departure(ride), link);
            best[key] = label;
            queue.add(label);
        }
    }

    // ---- forward: earliest arrivals ----

    @Override
    public TimedTree departingAt(long originStationId, Instant leaveAt) {
        requireStation(originStationId);
        return forward(originStationId, leaveAt, null);
    }

    /** Earliest arrivals; with a {@code target} station it stops once that station is reached. */
    private TimedTree forward(long originStationId, Instant leaveAt, Long target) {
        ServiceTime service = serviceTime(leaveAt);
        LocalDate date = service.serviceDate();
        DayType day = service.dayType();
        int start = service.secondOfServiceDay() + accessSeconds;
        // After the last train of the day the next trains are the first ones of the next service day.
        OptionalInt last = timetable.lastDepartureSec(day);
        if (last.isPresent() && start > last.getAsInt()) {
            date = date.plusDays(1);
            day = clock.dayTypeOf(date);
            start -= 86_400;
        }
        TrainTimetable.Rides[] dayRides = rides[day.ordinal()];
        PriorityQueue<Label> queue = new PriorityQueue<>(EARLIEST_FIRST);
        Label[] best = new Label[edges.length + nodeIds.length];
        for (Node node : graph.nodesOf(originStationId)) {
            int n = nodeIndex.get(node.id());
            Label label = new Label(start, 0, n, edges.length + n, null, null, null);
            best[label.key()] = label;
            queue.add(label);
        }
        boolean[] settledAny = new boolean[nodeIds.length];
        boolean[] settledRide = new boolean[nodeIds.length];
        int[] skipped = new int[nodeIds.length];
        Arrays.fill(skipped, -1);
        Map<Long, Label> arrivals = new HashMap<>();
        while (!queue.isEmpty()) {
            Label state = queue.poll();
            if (best[state.key()] != state) continue;
            int node = state.node();
            boolean junction = junctionNode[node];
            int uturn = state.ride() == null ? -1 : edgeFrom[state.key()];
            int onlyTo = -1;
            boolean relaxRides = junction || !settledAny[node];
            if (!relaxRides && skipped[node] >= 0 && skipped[node] != uturn) {
                relaxRides = true;
                onlyTo = skipped[node];
                skipped[node] = -1;
            }
            boolean relaxWalks = state.ride() != null && !settledRide[node];
            if (!relaxRides && !relaxWalks) continue;
            boolean first = !settledAny[node];
            settledAny[node] = true;
            if (state.ride() != null) {
                settledRide[node] = true;
                long station = graph.node(nodeIds[node]).stationId();
                if (station != originStationId) arrivals.putIfAbsent(station, state);
                if (target != null && station == target) break;
            }
            for (int e : outgoing[node]) {
                Edge edge = edges[e];
                int to = edgeTo[e];
                if (edge.type() == StepType.RIDE) {
                    if (!relaxRides || (onlyTo >= 0 && to != onlyTo)) continue;
                    if (to == uturn) {
                        if (first && !junction) skipped[node] = to;
                        continue;
                    }
                    TrainTimetable.Rides edgeRides = dayRides[e];
                    if (edgeRides == null) continue;
                    int change = state.ride() == null ? 0 : changeSeconds(state.edge().fromNodeId(), nodeIds[node], nodeIds[to]);
                    int ride = edgeRides.next(state.time() + change);
                    if (ride < 0) continue;
                    offer(best, queue, edgeRides.arriveSec(ride), state.transfers() + (change > 0 ? 1 : 0), to, e, edge,
                            edgeRides, ride, state, false);
                } else if (relaxWalks) {
                    offer(best, queue, state.time() + edge.seconds(), state.transfers() + 1, to, e, edge, null, -1, state, false);
                }
            }
        }
        LocalDate serviceDate = date;
        DayType serviceDay = day;
        return new TimedTree() {
            @Override
            public Set<Long> reachableStationIds() {
                return Collections.unmodifiableSet(new TreeSet<>(arrivals.keySet()));
            }

            @Override
            public Optional<TimedRoute> routeTo(long stationId) {
                Label end = arrivals.get(stationId);
                if (end == null) return Optional.empty();
                List<Label> moves = new ArrayList<>();
                for (Label cursor = end; cursor.edge() != null; cursor = cursor.link()) moves.add(0, cursor);
                return Optional.of(build(stayOnBoard(moves, serviceDay), serviceDate, originStationId, stationId));
            }
        };
    }

    // ---- route assembly ----

    /**
     * Keeps riding the train already on board when it runs the next edge and arrives there no later than the train the
     * search picked. Both searches only compare times, so a later train of the same line can win a tie at a stop
     * (마천 -> 여의도: the 마천 train runs on to 방화, but a later train from 강동 arrives just in time too). Every
     * following move still fits: it leaves the same node at the same time and the stop is reached no later.
     */
    private List<Label> stayOnBoard(List<Label> moves, DayType day) {
        List<Label> kept = new ArrayList<>(moves);
        for (int i = 1; i < kept.size(); i++) {
            Label previous = kept.get(i - 1);
            Label move = kept.get(i);
            if (previous.ride() == null || move.ride() == null || previous.ride().train().equals(move.ride().train())) continue;
            Optional<Departure> same = timetable.rideOf(move.edge().id(), day, previous.ride().train(), previous.ride().arriveSec());
            if (same.isPresent() && same.get().arriveSec() <= move.ride().arriveSec()) {
                kept.set(i, new Label(move.time(), move.transfers(), move.node(), move.key(), move.edge(), same.get(), move.link()));
            }
        }
        return kept;
    }

    /**
     * Builds the route from its moves in travel order. Each move is one edge with its train (RIDE) or walk (TRANSFER);
     * the label's own node is not used here, only its edge and ride.
     */
    private TimedRoute build(List<Label> moves, LocalDate serviceDate, long fromStationId, long toStationId) {
        Instant midnight = serviceDate.atStartOfDay(ZONE).toInstant();
        List<TimedLeg> legs = new ArrayList<>();
        List<PathStep> steps = new ArrayList<>();
        List<PathStop> stops = new ArrayList<>();
        int boardSec = moves.get(0).ride().departSec();
        int cursor = boardSec;
        int rideDistance = 0;
        // open RIDE step
        Edge stepFirst = null;
        Edge stepLast = null;
        int stepEdges = 0;
        int stepMeters = 0;
        // open leg
        Departure legFirst = null;
        Departure legLast = null;
        Edge legFirstEdge = null;
        Edge legLastEdge = null;
        int legEdges = 0;
        int walk = 0;
        int legWalk = 0;
        int legWait = 0;
        Departure previousRide = null;
        Edge previousEdge = null;

        Node first = graph.node(moves.get(0).edge().fromNodeId());
        stops.add(stop(first, moves.get(0).edge().direction(), 0));
        for (int index = 0; index < moves.size(); index++) {
            Label move = moves.get(index);
            Edge edge = move.edge();
            if (edge.type() == StepType.TRANSFER) {
                if (stepFirst != null) {
                    cursor = closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
                    stepFirst = null;
                }
                walk += edge.seconds();
                Node to = graph.node(edge.toNodeId());
                Line line = graph.line(to.lineId());
                Station station = graph.station(to.stationId()).orElseThrow();
                // The transfer lasts until the next train leaves; the next move is always a ride.
                int next = nextRide(moves, index).departSec();
                steps.add(new PathStep(StepType.TRANSFER, line.id(), line.name(), line.color(), null, station.id(), station.name(),
                        station.id(), station.name(), 0, next - cursor, edge.meters()));
                cursor = next;
                previousEdge = edge;
                continue;
            }
            Departure ride = move.ride();
            boolean branchChange = previousEdge != null && previousEdge.type() == StepType.RIDE
                    && changeSeconds(previousEdge.fromNodeId(), edge.fromNodeId(), edge.toNodeId()) > 0;
            boolean newTrain = previousRide == null || !previousRide.train().equals(ride.train()) || previousEdge.type() == StepType.TRANSFER;
            if (branchChange) {
                cursor = closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
                stepFirst = null;
                Node junction = graph.node(edge.fromNodeId());
                Line line = graph.line(junction.lineId());
                Station station = graph.station(junction.stationId()).orElseThrow();
                walk += changeSeconds(previousEdge.fromNodeId(), edge.fromNodeId(), edge.toNodeId());
                steps.add(new PathStep(StepType.TRANSFER, line.id(), line.name(), line.color(), null, station.id(), station.name(),
                        station.id(), station.name(), 0, ride.departSec() - cursor, changeMeters(junction.id())));
                cursor = ride.departSec();
                stops.add(stop(junction, edge.direction(), cursor - boardSec));
            } else if (previousEdge != null && previousEdge.type() == StepType.TRANSFER) {
                stops.add(stop(graph.node(edge.fromNodeId()), edge.direction(), cursor - boardSec));
            } else if (stepFirst != null && (stepFirst.direction() != edge.direction()
                    || graph.node(stepFirst.fromNodeId()).lineId() != graph.node(edge.fromNodeId()).lineId())) {
                // Same train or platform, new direction (응암 LOOP -> DOWN): a new RIDE step without a transfer.
                cursor = closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
                stepFirst = null;
                PathStop last = stops.remove(stops.size() - 1);
                stops.add(new PathStop(last.stationId(), last.stationName(), last.latitude(), last.longitude(), last.stationLineId(),
                        last.lineId(), edge.direction(), last.offsetSec()));
            }
            if (newTrain) {
                if (legFirst != null) legs.add(leg(legFirst, legLast, legFirstEdge, legLastEdge, legEdges, legWalk, legWait, midnight));
                legWalk = legFirst == null ? 0 : walk;
                legWait = legFirst == null ? 0 : ride.departSec() - previousRide.arriveSec() - walk;
                legFirst = ride;
                legFirstEdge = edge;
                legEdges = 0;
                walk = 0;
            }
            legLast = ride;
            legLastEdge = edge;
            legEdges++;
            if (stepFirst == null) {
                stepFirst = edge;
                stepEdges = 0;
                stepMeters = 0;
            }
            stepLast = edge;
            stepEdges++;
            stepMeters += edge.meters();
            rideDistance += edge.meters();
            stops.add(stop(graph.node(edge.toNodeId()), edge.direction(), ride.arriveSec() - boardSec));
            previousRide = ride;
            previousEdge = edge;
        }
        closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
        legs.add(leg(legFirst, legLast, legFirstEdge, legLastEdge, legEdges, legWalk, legWait, midnight));
        int transfers = (int) steps.stream().filter(step -> step.type() == StepType.TRANSFER).count();
        int arriveSec = previousRide.arriveSec();
        RoutePath path = new RoutePath(steps, stops, arriveSec - boardSec, transfers, rideDistance, fromStationId, toStationId);
        Instant boardAt = midnight.plusSeconds(boardSec);
        return new TimedRoute(path, legs, boardAt.minusSeconds(accessSeconds), boardAt, midnight.plusSeconds(arriveSec));
    }

    private static Departure nextRide(List<Label> moves, int after) {
        for (int i = after + 1; i < moves.size(); i++) {
            if (moves.get(i).ride() != null) return moves.get(i).ride();
        }
        throw new IllegalStateException("a transfer is always followed by a ride");
    }

    /** Adds the RIDE step ending at {@code endSec}; returns the new cursor. */
    private int closeRide(List<PathStep> steps, Edge first, Edge last, int edges, int meters, int endSec, int cursor) {
        Node from = graph.node(first.fromNodeId());
        Node to = graph.node(last.toNodeId());
        Line line = graph.line(from.lineId());
        Station fromStation = graph.station(from.stationId()).orElseThrow();
        Station toStation = graph.station(to.stationId()).orElseThrow();
        steps.add(new PathStep(StepType.RIDE, line.id(), line.name(), line.color(), first.direction(), fromStation.id(),
                fromStation.name(), toStation.id(), toStation.name(), edges, endSec - cursor, meters));
        return endSec;
    }

    private TimedLeg leg(Departure first, Departure last, Edge firstEdge, Edge lastEdge, int edges, int walk, int wait, Instant midnight) {
        Node from = graph.node(firstEdge.fromNodeId());
        Node to = graph.node(lastEdge.toNodeId());
        Line line = graph.line(from.lineId());
        Station fromStation = graph.station(from.stationId()).orElseThrow();
        Station toStation = graph.station(to.stationId()).orElseThrow();
        return new TimedLeg(line.id(), line.name(), first.train().trainCode(), first.train().terminal(), firstEdge.direction(),
                fromStation.id(), fromStation.name(), toStation.id(), toStation.name(), midnight.plusSeconds(first.departSec()),
                midnight.plusSeconds(last.arriveSec()), edges, walk, wait);
    }

    private PathStop stop(Node node, DirectionCode direction, int offset) {
        Station station = graph.station(node.stationId()).orElseThrow();
        return new PathStop(station.id(), station.name(), station.latitude(), station.longitude(), node.id(), node.lineId(),
                direction, offset);
    }
}
