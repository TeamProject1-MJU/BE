package com.mju.linkro.subway.graph;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.*;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import com.mju.linkro.subway.timetable.TimetableClock;
import com.mju.linkro.subway.timetable.TimetableClock.ServiceTime;
import com.mju.linkro.subway.timetable.TimetableProperties.TransferOverride;
import com.mju.linkro.subway.timetable.TrainTimetable;
import com.mju.linkro.subway.timetable.TrainTimetable.Departure;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import lombok.extern.slf4j.Slf4j;

/**
 * Time-dependent routes on the trains of {@link TrainTimetable} (#96).
 *
 * <p>Both searches are Dijkstra over states (node, the edge used at that node). Riding an edge means taking one real
 * train of it: backwards the latest train that reaches the next stop in time ({@code lastDepartureArriving}),
 * forwards the next train ({@code nextDeparture}). The wait is whatever lies between two trains, so staying on one
 * train waits nothing and a change waits for the next train. A TRANSFER edge adds its time before that wait: the seed's
 * walking seconds (station_connections), or the {@code transfer-overrides} seconds for the pairs listed there (#151,
 * 카카오맵 transfer times; timetable routes only, the graph and its distances stay). There is no fixed transfer wait and
 * no dwell setting. Changing trains on the same platform is free,
 * except at a branch junction (#56) where {@link BranchRules} needs a change, and at 응암 순환 -> 순환 and 강동 천호 side
 * <-> 마천 (#183): there the junction's {@code timetable-changes} seconds (else {@code branch-transfers}) are the least
 * time to change, and the change is a transfer. Times are seconds of the service day ({@link TimetableClock}), so trains
 * after midnight keep counting past 86400.
 *
 * <p>A ride never goes straight back to the station it came from (A -> B, then B -> A): waiting for a train back is
 * never a real route, and it would dodge the junction change walk. Only junction nodes keep one state per edge; at
 * other nodes the best state dominates, because the next train only depends on the time. Its transfers do not (#185):
 * a state settled later that needs fewer transfers than every state that already looked for trains at that node looks
 * again (a train ridden through the station after a walk reached it first), at most once per fewer transfer count.
 * Times still come first; the offers only win ties with fewer transfers.
 */
@Slf4j
public class TimetableRouter implements TimetableRouteFinder {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final SubwayGraph graph;
    private final TrainTimetable timetable;
    private final TimetableClock clock;
    private final int accessSeconds;
    private final Duration noServiceWindow;
    private final SubwayGraphProperties costs;
    private final Map<BranchRules.Junction, SubwayGraphProperties.BranchTransfer> branchTransfers = new HashMap<>();
    /** Timetable-only change times (#183), before {@link #branchTransfers}. */
    private final Map<BranchRules.Junction, Integer> timetableChanges = new HashMap<>();
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

    /** Without transfer overrides: every TRANSFER edge keeps its seed seconds. */
    public TimetableRouter(SubwayGraph graph, TrainTimetable timetable, TimetableClock clock, SubwayGraphProperties costs,
                           int accessSeconds, Duration noServiceWindow) {
        this(graph, timetable, clock, costs, accessSeconds, noServiceWindow, List.of());
    }

    /**
     * @param accessSeconds     from leaving to standing on the first platform, added before the first train
     * @param noServiceWindow   how long before an appointment some train must arrive for the subway to count as running
     * @param transferOverrides seconds replacing the TRANSFER edges of these pairs, both directions (#151)
     * @throws IllegalArgumentException when an override does not match one transfer of the graph, repeats a pair, or is
     *         negative ({@link #overrideTransfers})
     */
    public TimetableRouter(SubwayGraph graph, TrainTimetable timetable, TimetableClock clock, SubwayGraphProperties costs,
                           int accessSeconds, Duration noServiceWindow, List<TransferOverride> transferOverrides) {
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
        costs.getTimetableChanges().forEach(change ->
                timetableChanges.put(new BranchRules.Junction(change.getStation(), change.getLine()), change.getSeconds()));
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
        overrideTransfers(graph, edges, transferOverrides);
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

    private static final String OVERRIDES = "linkro.subway.timetable.transfer-overrides";

    /**
     * Replaces the seconds of the TRANSFER edges named by {@code overrides} in {@code edges}, both directions; the
     * distance stays. Each entry must name one station by its stored name, two different lines stopping there and joined
     * by TRANSFER edges both ways, and non-negative seconds; a pair (in either line order) may appear once.
     */
    private static void overrideTransfers(SubwayGraph graph, Edge[] edges, List<TransferOverride> overrides) {
        if (overrides.isEmpty()) return;
        Map<List<Long>, Integer> transferIndex = new HashMap<>();
        for (int e = 0; e < edges.length; e++) {
            if (edges[e].type() == StepType.TRANSFER) transferIndex.put(List.of(edges[e].fromNodeId(), edges[e].toNodeId()), e);
        }
        Map<List<Long>, Integer> seen = new HashMap<>();
        Map<Integer, Integer> seconds = new HashMap<>();
        for (int i = 0; i < overrides.size(); i++) {
            TransferOverride entry = overrides.get(i);
            String at = OVERRIDES + "[" + i + "] (" + entry + ")";
            if (blank(entry.getStation()) || blank(entry.getFromLine()) || blank(entry.getToLine())) {
                throw new IllegalArgumentException(at + ": station, from-line and to-line are required");
            }
            if (entry.getSeconds() < 0) throw new IllegalArgumentException(at + ": seconds must not be negative");
            if (entry.getFromLine().equals(entry.getToLine())) throw new IllegalArgumentException(at + ": the two lines are the same");
            List<Station> named = graph.stations().stream().filter(station -> station.name().equals(entry.getStation())).toList();
            if (named.isEmpty()) throw new IllegalArgumentException(at + ": no station named " + entry.getStation());
            if (named.size() > 1) throw new IllegalArgumentException(at + ": " + named.size() + " stations named " + entry.getStation());
            long stationId = named.get(0).id();
            long from = nodeOn(graph, stationId, entry.getFromLine(), at);
            long to = nodeOn(graph, stationId, entry.getToLine(), at);
            Integer there = transferIndex.get(List.of(from, to));
            Integer back = transferIndex.get(List.of(to, from));
            if (there == null || back == null) {
                throw new IllegalArgumentException(at + ": no TRANSFER edges both ways between lines " + entry.getFromLine()
                        + " and " + entry.getToLine() + " at " + entry.getStation());
            }
            Integer earlier = seen.putIfAbsent(List.of(Math.min(from, to), Math.max(from, to)), i);
            if (earlier != null) throw new IllegalArgumentException(at + ": the same pair as " + OVERRIDES + "[" + earlier + "]");
            seconds.put(there, entry.getSeconds());
            seconds.put(back, entry.getSeconds());
        }
        int changed = 0;
        for (Map.Entry<Integer, Integer> override : seconds.entrySet()) {
            Edge edge = edges[override.getKey()];
            if (edge.seconds() != override.getValue()) changed++;
            edges[override.getKey()] = new Edge(edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.type(), edge.direction(),
                    override.getValue(), edge.meters());
        }
        log.info("Timetable transfer overrides: {} pairs, {} directed TRANSFER edges ({} changed from the seed)",
                overrides.size(), seconds.size(), changed);
    }

    private static long nodeOn(SubwayGraph graph, long stationId, String lineCode, String at) {
        return graph.nodesOf(stationId).stream().filter(node -> graph.line(node.lineId()).code().equals(lineCode))
                .map(Node::id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(at + ": line " + lineCode + " does not stop there"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Seconds of a TRANSFER or RIDE edge as the searches use them (overrides applied), for tests. */
    int edgeSeconds(long edgeId) {
        return Arrays.stream(edges).filter(edge -> edge.id() == edgeId).findFirst().orElseThrow().seconds();
    }

    /** Distance of an edge as the routes report it, for tests. */
    int edgeMeters(long edgeId) {
        return Arrays.stream(edges).filter(edge -> edge.id() == edgeId).findFirst().orElseThrow().meters();
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
        return timetableChangeSeconds(junction);
    }

    /**
     * Least time to change trains at {@code junction} on timetable routes: its timetable-changes seconds (#183), else its
     * branch-transfers seconds, else default-branch-transfer-seconds.
     */
    private int timetableChangeSeconds(BranchRules.Junction junction) {
        Integer seconds = timetableChanges.get(junction);
        if (seconds != null) return seconds;
        var transfer = branchTransfers.get(junction);
        return transfer == null ? costs.getDefaultBranchTransferSeconds() : transfer.getSeconds();
    }

    /**
     * Arm pairs that {@link BranchRules} lets one train run through for the fixed-cost graph, where a timetable route that
     * changes trains makes a transfer (#183, as 카카오맵): 6호선 응암 순환 -> 순환 (no train goes round twice, #104) and 5호선
     * 강동 천호 side <-> 마천 (방화 trains run through, 강동-origin and 강동-bound ones do not). Riding one train through
     * stays free. Kept here, not in BranchRules, so the fixed-cost graph is unchanged.
     */
    private static final Set<List<String>> CHANGE_ONLY_ON_TIMETABLE = Set.of(List.of("응암", "6", "EUNGAM_LOOP", "EUNGAM_LOOP"),
            List.of("강동", "5", BranchRules.MAIN_LOWER, "MACHEON"), List.of("강동", "5", "MACHEON", BranchRules.MAIN_LOWER));

    /**
     * Least time between arriving at junction {@code node} and leaving on another train, for the pairs above
     * ({@link #timetableChangeSeconds}, the same lookup as {@link #changeSeconds}). Changing trains there is a transfer
     * (#183): it counts in the search and makes a TRANSFER step ({@link #build}). Staying on board waits nothing.
     */
    private int minimumChangeSeconds(long previous, long node, long next) {
        BranchRules.Junction junction = graph.junction(node);
        if (junction == null || !CHANGE_ONLY_ON_TIMETABLE.contains(List.of(junction.stationName(), junction.lineCode(),
                String.valueOf(graph.armAt(node, previous)), String.valueOf(graph.armAt(node, next))))) {
            return 0;
        }
        return timetableChangeSeconds(junction);
    }

    /**
     * Junctions where a change of train that the step rules would hide gets a RIDE step of its own (#179): the train
     * ridden so far goes on from the junction on another arm, so the rider has to leave it there. 5호선 강동 and 6호선 응암
     * (#181). Since #183 the changes 응암 순환 -> 순환 and 강동 천호 side <-> 마천 are transfers ({@link #CHANGE_ONLY_ON_TIMETABLE});
     * this split remains for the other arm pairs of these junctions. 2호선 keeps its steps.
     */
    private static final Set<BranchRules.Junction> SPLIT_ON_TRAIN_CHANGE =
            Set.of(new BranchRules.Junction("강동", "5"), new BranchRules.Junction("응암", "6"));

    /** Longest dwell looked at when asking whether the train ridden so far leaves a junction on another arm. */
    private static final int SPLIT_LOOKAHEAD_SECONDS = 600;

    /**
     * Junctions and the two arms between which a route rides one train through when such a train exists, as 카카오맵 does
     * (#181): 5호선 강동 between the main line toward 천호 and the 마천 branch. Both searches only compare times, so they
     * may take a 하남검단산 train to 강동 and a 강동-origin 마천 train from there even when a 마천 train runs through
     * (천호 17:20 -> 올림픽공원: 5127 and 5647 at 17:33:30 instead of 5649 at 17:38:20). Not 2호선 or 6호선.
     */
    private static final Map<BranchRules.Junction, Set<String>> THROUGH_FIRST =
            Map.of(new BranchRules.Junction("강동", "5"), Set.of(BranchRules.MAIN_LOWER, "MACHEON"));

    /**
     * Walking seconds of a branch change at junction {@code node}: its branch-transfers seconds, else
     * default-branch-transfer-seconds. Not {@link #timetableChangeSeconds}, which is the least time to change trains
     * and may include waiting on the platform (강동 60 s against a 16 s walk).
     */
    private int branchWalkSeconds(long node) {
        var transfer = branchTransfers.get(graph.junction(node));
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
        Through through = new Through(true);
        Map<Long, TimedRoute> routes = latestDepartures(destinationStationId, arriveBy, originStationIds, maxTravelSeconds, null, through);
        if (!through.used) return routes;
        // The guard of #181: per origin, the route without riding through when it needs fewer transfers.
        Map<Long, TimedRoute> plain = latestDepartures(destinationStationId, arriveBy, originStationIds, maxTravelSeconds, null,
                new Through(false));
        Map<Long, TimedRoute> guarded = new HashMap<>(routes);
        plain.forEach((origin, route) -> guarded.merge(origin, route, Through::fewerTransfers));
        return guarded;
    }

    /**
     * @param blocked nodes whose TRANSFER edges are not walked (#153), or null for none
     * @param through whether to ride through {@link #THROUGH_FIRST} junctions (#181), noting when it did
     */
    private Map<Long, TimedRoute> latestDepartures(long destinationStationId, Instant arriveBy, Set<Long> originStationIds,
                                                   int maxTravelSeconds, boolean[] blocked, Through through) {
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
        int[] rideTransfers = new int[nodeIds.length];
        Arrays.fill(rideTransfers, Integer.MAX_VALUE);
        while (!queue.isEmpty() && resolved.size() < wanted) {
            Label state = queue.poll();
            if (best[state.key()] != state) continue;
            int node = state.node();
            boolean junction = junctionNode[node];
            int uturn = state.ride() == null ? -1 : edgeTo[state.key()];
            int onlyFrom = -1;
            boolean relaxRides = junction || !settledAny[node];
            if (!relaxRides && state.transfers() < rideTransfers[node]) {
                // #185: fewer transfers than every state that looked for trains here (a train ridden through after a walk).
                relaxRides = true;
                if (skipped[node] >= 0 && skipped[node] != uturn) skipped[node] = -1;
            } else if (!relaxRides && skipped[node] >= 0 && skipped[node] != uturn) {
                // The best state here goes back to that neighbour; this one gives it a way forward.
                relaxRides = true;
                onlyFrom = skipped[node];
                skipped[node] = -1;
            }
            boolean relaxWalks = !state.walked() && state.edge() != null && !settledBoarding[node];
            if (!relaxRides && !relaxWalks) continue;
            boolean first = !settledAny[node];
            settledAny[node] = true;
            if (relaxRides && onlyFrom < 0) rideTransfers[node] = Math.min(rideTransfers[node], state.transfers());
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
                    int minimum = state.ride() == null ? 0 : minimumChangeSeconds(nodeIds[from], nodeIds[node], state.edge().toNodeId());
                    if (minimum > 0 && ride >= 0 && !edgeRides.departure(ride).train().equals(state.ride().train())) {
                        // Another train: it must arrive early enough to make the change (#104).
                        ride = edgeRides.lastArriving(state.time() - change - minimum);
                    }
                    if (ride < 0 || edgeRides.departSec(ride) < floor) continue;
                    int transfers = state.transfers() + (change > 0 || changesTrain(minimum, edgeRides, ride, state) ? 1 : 0);
                    offer(best, queue, edgeRides.departSec(ride), transfers, from, e, edge, edgeRides, ride, state, true);
                } else if (relaxWalks && state.ride() != null && (blocked == null || !blocked[node])) {
                    int time = state.time() - edge.seconds();
                    if (time >= floor) offer(best, queue, time, state.transfers() + 1, from, e, edge, null, -1, state, true);
                }
            }
        }
        Map<Long, TimedRoute> routes = new HashMap<>();
        resolved.forEach((origin, state) -> {
            List<Label> moves = new ArrayList<>();
            for (Label cursor = state; cursor.edge() != null; cursor = cursor.link()) moves.add(cursor);
            List<Label> kept = preferThrough(stayOnBoard(moves, day), day, floor, false, through);
            TimedRoute latest = build(kept, day, service.serviceDate(), origin, destinationStationId);
            // The backward search fixes when to leave; from then on the member takes the earliest way there
            // (장암 -> 모란: an earlier 5호선 and 8호선 connection). Only when it needs no more transfers: one minute is not
            // worth two extra changes (강남 -> 홍대입구 at 22:00 stays on 2호선).
            TimedRoute replayed = forward(origin, latest.leaveAt(), destinationStationId, blocked).routeTo(destinationStationId, through)
                    .orElse(latest);
            boolean better = replayed.arriveAt().isBefore(latest.arriveAt()) && noWorse(replayed, latest);
            routes.put(origin, better ? replayed : latest);
        });
        return routes;
    }

    /** A change of train at a {@link #CHANGE_ONLY_ON_TIMETABLE} pair ({@code minimum > 0}): a transfer (#183). */
    private static boolean changesTrain(int minimum, TrainTimetable.Rides edgeRides, int ride, Label state) {
        return minimum > 0 && !edgeRides.departure(ride).train().equals(state.ride().train());
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
        return departing(originStationId, leaveAt, null);
    }

    /**
     * {@link #departingAt(long, Instant)} without walking the TRANSFER edges at {@code avoidTransferStations} (#153), in
     * the forward search and in the backward search of {@link #withFewerTransfers}. Riding through those stations and
     * branch changes there are still allowed. An empty set is {@link #departingAt(long, Instant)}.
     */
    @Override
    public TimedTree departingAt(long originStationId, Instant leaveAt, Set<Long> avoidTransferStations) {
        requireStation(originStationId);
        return departing(originStationId, leaveAt, blockedNodes(avoidTransferStations));
    }

    /** Nodes of the stations, or null when there are none. Unknown station ids block nothing. */
    private boolean[] blockedNodes(Set<Long> stationIds) {
        if (stationIds.isEmpty()) return null;
        boolean[] blocked = new boolean[nodeIds.length];
        for (long stationId : stationIds) {
            for (Node node : graph.nodesOf(stationId)) blocked[nodeIndex.get(node.id())] = true;
        }
        return blocked;
    }

    private TimedTree departing(long originStationId, Instant leaveAt, boolean[] blocked) {
        Tree earliest = forward(originStationId, leaveAt, null, blocked);
        return new TimedTree() {
            @Override
            public Set<Long> reachableStationIds() {
                return earliest.reachableStationIds();
            }

            @Override
            public Optional<TimedRoute> routeTo(long stationId) {
                Through through = new Through(true);
                Optional<TimedRoute> route = earliest.routeTo(stationId, through)
                        .map(found -> withFewerTransfers(found, originStationId, stationId, leaveAt, blocked, through));
                Optional<TimedRoute> without = Optional.empty();
                Optional<TimedRoute> result = route;
                if (through.used && route.isPresent()) {
                    // The guard of #181: riding through must not cost a transfer (영등포구청 16:10 -> 오금: the 마천 train
                    // through from 왕십리 led to 2호선 and 3호선 instead of the 5호선 trains changed at 강동).
                    Through plain = new Through(false);
                    without = earliest.routeTo(stationId, plain)
                            .map(found -> withFewerTransfers(found, originStationId, stationId, leaveAt, blocked, plain));
                    result = without.map(other -> Through.fewerTransfers(route.get(), other));
                }
                if (result.isEmpty()) return result;
                // #183: a change of train at a THROUGH_FIRST junction left in the result or in either route the guard
                // compared gives way to one train from the origin to the destination, when there is one.
                if (changesAtThroughFirst(result.get()) || route.filter(TimetableRouter.this::changesAtThroughFirst).isPresent()
                        || without.filter(TimetableRouter.this::changesAtThroughFirst).isPresent()) {
                    Optional<TimedRoute> direct = earliest.directTrain(stationId);
                    if (direct.isPresent()) return direct;
                }
                return result;
            }
        };
    }

    /**
     * The forward search keeps only the earliest state at ordinary nodes, so of two ways arriving at the same time it can
     * keep the one with more transfers (불광 -> 디지털미디어시티: 3호선 to 연신내 and a walk to the very 6호선 train that
     * left 불광 a minute later). Searching backwards from that arrival, as {@link #latestDepartures} does, finds the
     * latest way to make it; it is used when it needs no more transfers, arrives no later and leaves no earlier than
     * asked (#107), and only when it is better: fewer transfers, or as many and leaving later. An equally good route
     * keeps the forward one, so results do not move between equivalent routes. The mirror of the forward replay in
     * latestDepartures.
     */
    private TimedRoute withFewerTransfers(TimedRoute earliest, long originStationId, long stationId, Instant leaveAt,
                                          boolean[] blocked, Through through) {
        int maxTravel = (int) Duration.between(leaveAt, earliest.arriveAt()).toSeconds();
        TimedRoute backward = latestDepartures(stationId, earliest.arriveAt(), Set.of(originStationId), Math.max(maxTravel, 1),
                blocked, through).get(originStationId);
        if (backward == null || !noWorse(backward, earliest) || backward.leaveAt().isBefore(leaveAt)) return earliest;
        boolean fewerTransfers = backward.path().transferCount() < earliest.path().transferCount();
        return fewerTransfers || backward.leaveAt().isAfter(earliest.leaveAt()) ? backward : earliest;
    }

    /** Needs no more transfers and arrives no later. */
    private static boolean noWorse(TimedRoute candidate, TimedRoute current) {
        return candidate.path().transferCount() <= current.path().transferCount()
                && !candidate.arriveAt().isAfter(current.arriveAt());
    }

    @Override
    public Map<Long, Instant> earliestArrivalsOfServiceDay(long originStationId, Instant at) {
        requireStation(originStationId);
        ServiceTime service = serviceTime(at);
        // Leaving at second 0 of the service day: every train of the day can be taken, so these are the first trains.
        Forward search = search(originStationId, service.serviceDate(), service.dayType(), accessSeconds, null, null);
        Instant midnight = service.serviceDate().atStartOfDay(ZONE).toInstant();
        Map<Long, Instant> arrivals = new HashMap<>();
        search.arrivals().forEach((station, label) -> arrivals.put(station, midnight.plusSeconds(label.time())));
        return arrivals;
    }

    /**
     * Earliest arrivals; with a {@code target} station it stops once that station is reached. {@code blocked} nodes do not
     * walk their TRANSFER edges (null for none).
     */
    private Tree forward(long originStationId, Instant leaveAt, Long target, boolean[] blocked) {
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
        return new Tree(search(originStationId, date, day, start, target, blocked), originStationId);
    }

    /** Earliest state at each station reached on a ride, from {@code start} (second of the service day) on. */
    /** @param start the second of the service day the search starts boarding at (leaving time plus station access) */
    private record Forward(LocalDate serviceDate, DayType day, int start, Map<Long, Label> arrivals) {}

    private Forward search(long originStationId, LocalDate date, DayType day, int start, Long target, boolean[] blocked) {
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
        int[] rideTransfers = new int[nodeIds.length];
        Arrays.fill(rideTransfers, Integer.MAX_VALUE);
        Map<Long, Label> arrivals = new HashMap<>();
        while (!queue.isEmpty()) {
            Label state = queue.poll();
            if (best[state.key()] != state) continue;
            int node = state.node();
            boolean junction = junctionNode[node];
            int uturn = state.ride() == null ? -1 : edgeFrom[state.key()];
            int onlyTo = -1;
            boolean relaxRides = junction || !settledAny[node];
            if (!relaxRides && state.transfers() < rideTransfers[node]) {
                // #185: fewer transfers than every state that looked for trains here (a train ridden through after a walk).
                relaxRides = true;
                if (skipped[node] >= 0 && skipped[node] != uturn) skipped[node] = -1;
            } else if (!relaxRides && skipped[node] >= 0 && skipped[node] != uturn) {
                relaxRides = true;
                onlyTo = skipped[node];
                skipped[node] = -1;
            }
            boolean relaxWalks = state.ride() != null && !settledRide[node];
            if (!relaxRides && !relaxWalks) continue;
            boolean first = !settledAny[node];
            settledAny[node] = true;
            if (relaxRides && onlyTo < 0) rideTransfers[node] = Math.min(rideTransfers[node], state.transfers());
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
                    int minimum = state.ride() == null ? 0 : minimumChangeSeconds(state.edge().fromNodeId(), nodeIds[node], nodeIds[to]);
                    if (minimum > 0 && ride >= 0 && !edgeRides.departure(ride).train().equals(state.ride().train())) {
                        // Another train: it can only be caught after the change time (#104).
                        ride = edgeRides.next(state.time() + change + minimum);
                    }
                    if (ride < 0) continue;
                    int transfers = state.transfers() + (change > 0 || changesTrain(minimum, edgeRides, ride, state) ? 1 : 0);
                    offer(best, queue, edgeRides.arriveSec(ride), transfers, to, e, edge, edgeRides, ride, state, false);
                } else if (relaxWalks && (blocked == null || !blocked[node])) {
                    offer(best, queue, state.time() + edge.seconds(), state.transfers() + 1, to, e, edge, null, -1, state, false);
                }
            }
        }
        return new Forward(date, day, start, arrivals);
    }

    /** The routes of one forward search; {@link #routeTo(long)} rides through {@link #THROUGH_FIRST} junctions (#181). */
    private final class Tree implements TimedTree {
        private final Forward search;
        private final long originStationId;

        Tree(Forward search, long originStationId) {
            this.search = search;
            this.originStationId = originStationId;
        }

        @Override
        public Set<Long> reachableStationIds() {
            return Collections.unmodifiableSet(new TreeSet<>(search.arrivals().keySet()));
        }

        @Override
        public Optional<TimedRoute> routeTo(long stationId) {
            return routeTo(stationId, new Through(true));
        }

        /**
         * One train from the origin to {@code stationId} through a {@link #THROUGH_FIRST} junction between its two arms
         * (#183): the first that leaves the origin once the search starts boarding and rides every edge of the line's way
         * there. Empty when the two stations are not on those arms or no such train runs that service day.
         */
        Optional<TimedRoute> directTrain(long stationId) {
            List<Integer> way = throughFirstWay(originStationId, stationId);
            if (way == null) return Optional.empty();
            TrainTimetable.Rides[] dayRides = rides[search.day().ordinal()];
            TrainTimetable.Rides first = dayRides[way.get(0)];
            if (first == null) return Optional.empty();
            // No train left on the first edge from the start: none, rather than the earliest of the day (PR #184 review).
            int from = first.next(search.start());
            if (from < 0) return Optional.empty();
            for (int i = from; i < first.size(); i++) {
                Departure start = first.departure(i);
                List<Label> moves = new ArrayList<>(List.of(new Label(start.arriveSec(), 0, edgeTo[way.get(0)], way.get(0), edges[way.get(0)], start, null)));
                int at = start.arriveSec();
                for (int k = 1; k < way.size(); k++) {
                    int e = way.get(k);
                    Optional<Departure> ride = timetable.rideOf(edges[e].id(), search.day(), start.train(), at);
                    if (ride.isEmpty() || ride.get().departSec() - at > SPLIT_LOOKAHEAD_SECONDS) break;
                    moves.add(new Label(ride.get().arriveSec(), 0, edgeTo[e], e, edges[e], ride.get(), null));
                    at = ride.get().arriveSec();
                }
                if (moves.size() == way.size()) {
                    return Optional.of(build(moves, search.day(), search.serviceDate(), originStationId, stationId));
                }
            }
            return Optional.empty();
        }

        Optional<TimedRoute> routeTo(long stationId, Through through) {
            Label end = search.arrivals().get(stationId);
            if (end == null) return Optional.empty();
            List<Label> moves = new ArrayList<>();
            for (Label cursor = end; cursor.edge() != null; cursor = cursor.link()) moves.add(0, cursor);
            List<Label> kept = preferThrough(stayOnBoard(moves, search.day()), search.day(), search.start(), true, through);
            return Optional.of(build(kept, search.day(), search.serviceDate(), originStationId, stationId));
        }
    }

    /**
     * Whether {@link #preferThrough} applies (#181), and whether it changed a route. Only then is the route without it
     * worked out too, and that one is kept when it needs fewer transfers: riding through never costs a transfer.
     */
    private static final class Through {
        final boolean on;
        boolean used;

        Through(boolean on) {
            this.on = on;
        }

        /** {@code plain} when it needs fewer transfers than {@code through}, else {@code through}. */
        static TimedRoute fewerTransfers(TimedRoute through, TimedRoute plain) {
            return plain.path().transferCount() < through.path().transferCount() ? plain : through;
        }
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
     * Prefers riding one train through a {@link #THROUGH_FIRST} junction (#181). When the moves change trains there
     * between the two arms, the rides from the boarding of the first train to the alighting from the second are replaced
     * by one train running all of those edges (the same stations):
     *
     * <ul>
     *   <li>forward ({@code forward}): the first such train leaving the boarding station once the rider is there; when it
     *       arrives later, the following moves keep their edges and take the next rides from then on;</li>
     *   <li>backward: the latest such train arriving no later than the second train did, so nothing after it changes.</li>
     * </ul>
     *
     * Without such a train, or when the following moves cannot be ridden any more, the moves are kept: the change of train
     * stays, shown as its own RIDE step (#179), and so does it when riding through needs more transfers ({@link Through}).
     * No limit on how much later the through train may arrive (#181: decided after measuring).
     *
     * @param readyAtStart the earliest second the first train may leave the origin: forward, when the search starts
     *                     boarding; backward, the floor of the travel limit (a through train leaving earlier is over it)
     * @param through      off: the moves as they are; on: marked used when a train is replaced
     */
    private List<Label> preferThrough(List<Label> moves, DayType day, int readyAtStart, boolean forward, Through through) {
        if (!through.on) return moves;
        for (int i = 1; i < moves.size(); i++) {
            Label before = moves.get(i - 1);
            Label after = moves.get(i);
            if (before.ride() == null || after.ride() == null || before.ride().train().equals(after.ride().train())) continue;
            if (!throughFirst(before.edge(), after.edge())) continue;
            int first = i - 1;
            while (first > 0 && moves.get(first - 1).ride() != null && moves.get(first - 1).ride().train().equals(before.ride().train())) first--;
            int last = i;
            while (last + 1 < moves.size() && moves.get(last + 1).ride() != null
                    && moves.get(last + 1).ride().train().equals(after.ride().train())) last++;
            List<Label> replaced = throughTrain(moves, first, last, day, readyBefore(moves, first, readyAtStart), forward);
            if (replaced == null) return moves;
            through.used = true;
            return replaced;
        }
        return moves;
    }

    /** Junction -> station id -> arm, for the stations of the junction's line on each arm (#183), built once per junction. */
    private final Map<BranchRules.Junction, Map<Long, String>> throughFirstArms = new java.util.concurrent.ConcurrentHashMap<>();

    /** Arms of {@code junction}'s line, from each neighbour of its node outwards without passing the node again. */
    private Map<Long, String> armsOf(BranchRules.Junction junction) {
        return throughFirstArms.computeIfAbsent(junction, key -> {
            Map<Long, String> arms = new HashMap<>();
            graph.junctionNodes().forEach((nodeId, j) -> {
                if (!j.equals(key)) return;
                int junctionIndex = nodeIndex.get(nodeId);
                for (int e : outgoing[junctionIndex]) {
                    if (edges[e].type() != StepType.RIDE) continue;
                    String arm = graph.armAt(nodeId, nodeIds[edgeTo[e]]);
                    Deque<Integer> stack = new ArrayDeque<>(List.of(edgeTo[e]));
                    Set<Integer> seen = new HashSet<>(List.of(junctionIndex, edgeTo[e]));
                    while (!stack.isEmpty()) {
                        int n = stack.pop();
                        arms.putIfAbsent(graph.node(nodeIds[n]).stationId(), arm);
                        for (int next : outgoing[n]) {
                            if (edges[next].type() == StepType.RIDE && seen.add(edgeTo[next])) stack.push(edgeTo[next]);
                        }
                    }
                }
            });
            return arms;
        });
    }

    /**
     * The RIDE edges (indices) of the line's way from {@code from} to {@code to} when the two stations lie on the two
     * arms of a {@link #THROUGH_FIRST} junction, else null.
     */
    private List<Integer> throughFirstWay(long from, long to) {
        for (Map.Entry<BranchRules.Junction, Set<String>> entry : THROUGH_FIRST.entrySet()) {
            Map<Long, String> arms = armsOf(entry.getKey());
            String a = arms.get(from), b = arms.get(to);
            if (a == null || b == null || a.equals(b) || !entry.getValue().contains(a) || !entry.getValue().contains(b)) continue;
            Long lineId = null;
            for (var j : graph.junctionNodes().entrySet()) {
                if (j.getValue().equals(entry.getKey())) lineId = graph.node(j.getKey()).lineId();
            }
            Integer start = null, end = null;
            for (Node node : graph.nodesOf(from)) if (lineId != null && node.lineId() == lineId) start = nodeIndex.get(node.id());
            for (Node node : graph.nodesOf(to)) if (lineId != null && node.lineId() == lineId) end = nodeIndex.get(node.id());
            if (start == null || end == null) continue;
            // Breadth-first over the line's RIDE edges: the way between two stations of one line is unique.
            int[] via = new int[nodeIds.length];
            Arrays.fill(via, -1);
            Deque<Integer> queue = new ArrayDeque<>(List.of(start));
            boolean[] seen = new boolean[nodeIds.length];
            seen[start] = true;
            while (!queue.isEmpty() && !seen[end]) {
                int n = queue.poll();
                for (int e : outgoing[n]) {
                    if (edges[e].type() != StepType.RIDE || seen[edgeTo[e]]) continue;
                    seen[edgeTo[e]] = true;
                    via[edgeTo[e]] = e;
                    queue.add(edgeTo[e]);
                }
            }
            if (!seen[end]) continue;
            LinkedList<Integer> way = new LinkedList<>();
            for (int n = end; n != start; n = edgeFrom[via[n]]) way.addFirst(via[n]);
            return way;
        }
        return null;
    }

    /** The route changes trains at a {@link #THROUGH_FIRST} junction between its two arms (#183). */
    private boolean changesAtThroughFirst(TimedRoute route) {
        List<TimedLeg> legs = route.legs();
        for (Map.Entry<BranchRules.Junction, Set<String>> entry : THROUGH_FIRST.entrySet()) {
            Map<Long, String> arms = armsOf(entry.getKey());
            for (int i = 0; i + 1 < legs.size(); i++) {
                TimedLeg before = legs.get(i), after = legs.get(i + 1);
                if (before.lineId() != after.lineId() || before.toStationId() != after.fromStationId()) continue;
                if (!entry.getKey().stationName().equals(before.toStationName())) continue;
                String a = arms.get(before.fromStationId()), b = arms.get(after.toStationId());
                if (a != null && b != null && !a.equals(b) && entry.getValue().contains(a) && entry.getValue().contains(b)) return true;
            }
        }
        return false;
    }

    /** At a {@link #THROUGH_FIRST} junction, between its two arms (either way). */
    private boolean throughFirst(Edge into, Edge outOf) {
        long node = outOf.fromNodeId();
        BranchRules.Junction junction = graph.junction(node);
        Set<String> arms = junction == null ? null : THROUGH_FIRST.get(junction);
        if (arms == null || into.toNodeId() != node) return false;
        String from = graph.armAt(node, into.fromNodeId());
        String to = graph.armAt(node, outOf.toNodeId());
        return from != null && to != null && !from.equals(to) && arms.contains(from) && arms.contains(to);
    }

    /** When the rider is at the boarding station of move {@code first}: the ride before it plus walks, else the start. */
    private static int readyBefore(List<Label> moves, int first, int readyAtStart) {
        int walk = 0;
        for (int k = first - 1; k >= 0; k--) {
            Label move = moves.get(k);
            if (move.ride() != null) return move.ride().arriveSec() + walk;
            walk += move.edge().seconds();
        }
        return readyAtStart;
    }

    /** The moves with rides {@code first}..{@code last} on one train, or null when there is none (see preferThrough). */
    private List<Label> throughTrain(List<Label> moves, int first, int last, DayType day, int ready, boolean forward) {
        TrainTimetable.Rides boarding = rides[day.ordinal()][moves.get(first).key()];
        if (boarding == null) return null;
        int secondArrives = moves.get(last).ride().arriveSec();
        List<Departure> chosen = null;
        for (int i = Math.max(boarding.next(ready), 0); i >= 0 && i < boarding.size(); i++) {
            Departure start = boarding.departure(i);
            if (start.departSec() < ready) continue;
            if (!forward && start.departSec() > secondArrives) break;
            List<Departure> chain = sameTrain(moves, first, last, day, start);
            if (chain == null) continue;
            if (forward) {
                chosen = chain;
                break;
            }
            if (chain.get(chain.size() - 1).arriveSec() <= secondArrives) chosen = chain;
        }
        if (chosen == null) return null;
        List<Label> result = new ArrayList<>(moves.subList(0, first));
        for (int k = first; k <= last; k++) {
            Label move = moves.get(k);
            Departure ride = chosen.get(k - first);
            result.add(new Label(ride.arriveSec(), move.transfers(), move.node(), move.key(), move.edge(), ride, move.link()));
        }
        int arrives = chosen.get(chosen.size() - 1).arriveSec();
        List<Label> rest = arrives <= secondArrives ? moves.subList(last + 1, moves.size()) : retime(moves, last + 1, arrives, day);
        if (rest == null) return null;
        result.addAll(rest);
        return result;
    }

    /** One train's rides on the edges of moves {@code first}..{@code last}, starting with {@code start}, or null. */
    private List<Departure> sameTrain(List<Label> moves, int first, int last, DayType day, Departure start) {
        List<Departure> chain = new ArrayList<>(List.of(start));
        int at = start.arriveSec();
        for (int k = first + 1; k <= last; k++) {
            Optional<Departure> ride = timetable.rideOf(moves.get(k).edge().id(), day, start.train(), at);
            if (ride.isEmpty() || ride.get().departSec() - at > SPLIT_LOOKAHEAD_SECONDS) return null;
            chain.add(ride.get());
            at = ride.get().arriveSec();
        }
        return chain;
    }

    /**
     * Moves {@code from}.. again from second {@code at} on: walks as they are, each ride the next one leaving its edge (the
     * same train while the original stayed on one), until a move can be ridden as found; null when a ride is gone.
     */
    private List<Label> retime(List<Label> moves, int from, int at, DayType day) {
        List<Label> result = new ArrayList<>();
        int now = at;
        Departure previous = null;
        for (int k = from; k < moves.size(); k++) {
            Label move = moves.get(k);
            if (move.ride() == null) {
                now += move.edge().seconds();
                result.add(move);
                previous = null;
                continue;
            }
            if (move.ride().departSec() >= now) {
                result.addAll(moves.subList(k, moves.size()));
                return result;
            }
            boolean sameTrain = previous != null && moves.get(k - 1).ride() != null && moves.get(k - 1).ride().train().equals(move.ride().train());
            Optional<Departure> ride;
            if (sameTrain) {
                ride = timetable.rideOf(move.edge().id(), day, previous.train(), now);
            } else {
                TrainTimetable.Rides edgeRides = rides[day.ordinal()][move.key()];
                int index = edgeRides == null ? -1 : edgeRides.next(now);
                ride = index < 0 ? Optional.empty() : Optional.of(edgeRides.departure(index));
            }
            if (ride.isEmpty()) return null;
            result.add(new Label(ride.get().arriveSec(), move.transfers(), move.node(), move.key(), move.edge(), ride.get(), move.link()));
            previous = ride.get();
            now = ride.get().arriveSec();
        }
        return result;
    }

    /**
     * Builds the route from its moves in travel order. Each move is one edge with its train (RIDE) or walk (TRANSFER);
     * the label's own node is not used here, only its edge and ride.
     */
    private TimedRoute build(List<Label> moves, DayType day, LocalDate serviceDate, long fromStationId, long toStationId) {
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
            // #183: another train at a CHANGE_ONLY_ON_TIMETABLE pair (응암 순환 -> 순환, 강동 천호 side <-> 마천) is a transfer
            // too, whether the train ridden so far goes on or ends there. Its step lasts the whole wait; no walk is added.
            boolean trainChange = !branchChange && newTrain && previousEdge != null && previousEdge.type() == StepType.RIDE
                    && minimumChangeSeconds(previousEdge.fromNodeId(), edge.fromNodeId(), edge.toNodeId()) > 0;
            if (branchChange || trainChange) {
                cursor = closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
                stepFirst = null;
                Node junction = graph.node(edge.fromNodeId());
                Line line = graph.line(junction.lineId());
                Station station = graph.station(junction.stationId()).orElseThrow();
                // The walk is the measured one (branch-transfers), not the least change time above (PR #184 review):
                // 강동 하남 <-> 마천 walks 16 s and waits the rest of the 60 s or more.
                walk += branchChange ? branchWalkSeconds(junction.id()) : 0;
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
            } else if (newTrain && stepFirst != null && previousEdge.type() == StepType.RIDE
                    && leavesOnAnotherArm(previousRide, move.key(), edge.fromNodeId(), day)) {
                // Another train at a junction where the one ridden so far goes on along another arm (#179): a new RIDE
                // step without a transfer, as for the change of direction above. Times and transfer counts do not change.
                cursor = closeRide(steps, stepFirst, stepLast, stepEdges, stepMeters, previousRide.arriveSec(), cursor);
                stepFirst = null;
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

    /**
     * True when {@code previous} (the train ridden into {@code junctionNode}) leaves that junction, within
     * {@link #SPLIT_LOOKAHEAD_SECONDS}, on a RIDE edge other than {@code takenEdge}, at a {@link #SPLIT_ON_TRAIN_CHANGE}
     * junction. A train whose run ends there (2호선 성수 and 신도림, where the timetable starts a new run) does not.
     */
    private boolean leavesOnAnotherArm(Departure previous, int takenEdge, long junctionNode, DayType day) {
        BranchRules.Junction junction = graph.junction(junctionNode);
        if (junction == null || !SPLIT_ON_TRAIN_CHANGE.contains(junction)) return false;
        TrainTimetable.Rides[] dayRides = rides[day.ordinal()];
        for (int e : outgoing[nodeIndex.get(junctionNode)]) {
            TrainTimetable.Rides edgeRides = dayRides[e];
            if (e == takenEdge || edges[e].type() != StepType.RIDE || edgeRides == null) continue;
            for (int i = edgeRides.next(previous.arriveSec()); i >= 0 && i < edgeRides.size()
                    && edgeRides.departSec(i) <= previous.arriveSec() + SPLIT_LOOKAHEAD_SECONDS; i++) {
                if (edgeRides.departure(i).train().equals(previous.train())) return true;
            }
        }
        return false;
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
