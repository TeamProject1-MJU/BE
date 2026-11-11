package com.mju.linkro.subway.timetable;

import com.mju.linkro.subway.api.DayType;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.congestion.load.StationNames;
import com.mju.linkro.subway.graph.SubwayGraphData;
import com.mju.linkro.subway.graph.SubwayGraphData.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * Seoul Metro train timetable (train-timetable.csv.gz) mapped onto the RIDE edges of the subway graph (#94).
 *
 * <p>Stations are matched by (line, name) with {@link StationNames#normalize}; external station codes are not used.
 * Edges come from the stop order of each train, not from 상행/하행/내선/외선: a train is
 * (daytype, line, train_code, origin, terminal), its stops are sorted by depart_sec, and each pair of neighbouring
 * stops (A -> B) is a ride on the RIDE edge A -> B. Pairs without such an edge are only counted in {@link #report()}.
 * Express trains are skipped.
 *
 * <p>Times are seconds from midnight of the service day; trains after midnight keep counting past 86400 (25:10 is
 * 90600). Use {@link TimetableClock} to turn a date and time into a day type and such a second. Immutable and
 * thread-safe once built.
 */
@Slf4j
public final class TrainTimetable {
    static final String HEADER = "line,station,daytype,direction,express,train_code,arrive_sec,depart_sec,origin,terminal";
    static final String EXPRESS = "급행";
    static final String LOCAL = "일반";
    static final Map<String, DayType> DAY_TYPES = Map.of("평일", DayType.WEEKDAY, "토요일", DayType.SATURDAY,
            "일요일·공휴일", DayType.SUNDAY);

    /** One train of one day type. train_code alone is not unique (2호선 성수지선 trains share codes with others). */
    public record TrainKey(DayType dayType, String lineCode, String trainCode, String origin, String terminal) {}

    /** A ride on one edge: leaves A at {@code departSec}, reaches B at {@code arriveSec}. */
    public record Departure(int departSec, int arriveSec, TrainKey train) {}

    /** What loading kept and dropped. Maps are sorted and unmodifiable. */
    public record LoadReport(int rows, int expressSkipped, int unmappedRows, Map<String, Integer> unmappedStations, int trains,
                             int rides, int missingArrivals, int invalidRides, Map<String, Integer> pairsNotInGraph,
                             int rideEdges, int rideEdgesWithTrains, long approxBytes) {}

    private record Stop(long nodeId, String station, int arriveSec, int departSec) {}

    /** Rides of one edge and day type: sorted by departure, plus an arrival order for backward search. */
    private static final class Series {
        final int[] depart;
        final int[] arrive;
        final TrainKey[] trains;
        /** Arrival times in ascending order. */
        final int[] arriveSorted;
        /** For the first i + 1 rides by arrival, the index (in departure order) of the latest departure. */
        final int[] latestDepartureUpTo;

        Series(List<Departure> rides) {
            List<Departure> byDepart = new ArrayList<>(rides);
            byDepart.sort(Comparator.comparingInt(Departure::departSec).thenComparingInt(Departure::arriveSec));
            int n = byDepart.size();
            depart = new int[n];
            arrive = new int[n];
            trains = new TrainKey[n];
            for (int i = 0; i < n; i++) {
                depart[i] = byDepart.get(i).departSec();
                arrive[i] = byDepart.get(i).arriveSec();
                trains[i] = byDepart.get(i).train();
            }
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            Arrays.sort(order, Comparator.comparingInt((Integer i) -> arrive[i]).thenComparingInt(i -> depart[i]));
            arriveSorted = new int[n];
            latestDepartureUpTo = new int[n];
            for (int k = 0; k < n; k++) {
                arriveSorted[k] = arrive[order[k]];
                int best = k == 0 ? order[k] : latestDepartureUpTo[k - 1];
                latestDepartureUpTo[k] = depart[order[k]] > depart[best] ? order[k] : best;
            }
        }

        Departure at(int i) {
            return new Departure(depart[i], arrive[i], trains[i]);
        }

        Optional<Departure> next(int atOrAfter) {
            int i = firstAtLeast(depart, atOrAfter);
            return i < depart.length ? Optional.of(at(i)) : Optional.empty();
        }

        Optional<Departure> lastArriving(int arriveBy) {
            int k = firstAtLeast(arriveSorted, arriveBy + 1) - 1;
            return k >= 0 ? Optional.of(at(latestDepartureUpTo[k])) : Optional.empty();
        }

        long bytes() {
            return 16L * depart.length + 32;
        }
    }

    /** RIDE edge id -> day type -> rides. */
    private final Map<Long, Map<DayType, Series>> series;
    /** RIDE edges of supported nodes: from node -> to node -> edge id. */
    private final Map<Long, Map<Long, Long>> rideEdges;
    /** (station id, line id) -> node id. */
    private final Map<List<Long>, Long> nodesByStationLine;
    private final LoadReport report;

    private TrainTimetable(Map<Long, Map<DayType, Series>> series, Map<Long, Map<Long, Long>> rideEdges,
                           Map<List<Long>, Long> nodesByStationLine, LoadReport report) {
        this.series = series;
        this.rideEdges = rideEdges;
        this.nodesByStationLine = nodesByStationLine;
        this.report = report;
    }

    /** Reads the gzip CSV and maps it onto the graph data. Fails on a wrong header or a malformed row. */
    public static TrainTimetable load(InputStream gzip, SubwayGraphData graph) {
        long started = System.nanoTime();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(gzip), StandardCharsets.UTF_8))) {
            TrainTimetable timetable = build(reader, graph);
            LoadReport r = timetable.report;
            log.info("Train timetable loaded: {} rows, {} trains, {} rides on {}/{} RIDE edges in {} ms (~{} MB)",
                    r.rows(), r.trains(), r.rides(), r.rideEdgesWithTrains(), r.rideEdges(),
                    (System.nanoTime() - started) / 1_000_000, String.format(Locale.ROOT, "%.1f", r.approxBytes() / 1_048_576.0));
            log.info("Train timetable skipped {} express rows", r.expressSkipped());
            if (r.unmappedRows() > 0) {
                log.warn("Train timetable: {} rows at stations missing from our DB were skipped: {}", r.unmappedRows(), r.unmappedStations());
            }
            if (!r.pairsNotInGraph().isEmpty()) {
                log.warn("Train timetable: {} stop pairs are not RIDE edges of the graph and were not mapped: {}",
                        r.pairsNotInGraph().size(), r.pairsNotInGraph());
            }
            if (r.missingArrivals() > 0 || r.invalidRides() > 0) {
                log.warn("Train timetable: {} stops had no arrival time (departure used), {} rides did not move forward in time and were dropped",
                        r.missingArrivals(), r.invalidRides());
            }
            return timetable;
        } catch (IOException exception) {
            throw new UncheckedIOException("Train timetable could not be read", exception);
        }
    }

    static TrainTimetable build(BufferedReader reader, SubwayGraphData graph) throws IOException {
        Map<Long, Line> lines = new HashMap<>();
        graph.lines().forEach(line -> lines.put(line.id(), line));
        Map<Long, Station> stations = new HashMap<>();
        graph.stations().forEach(station -> stations.put(station.id(), station));

        // (line code, normalized name) -> node, for supported stations only (as in SubwayGraph).
        Map<String, Long> nodeByName = new HashMap<>();
        Map<List<Long>, Long> nodesByStationLine = new HashMap<>();
        Set<Long> supportedNodes = new HashSet<>();
        for (Node node : graph.nodes()) {
            Station station = stations.get(node.stationId());
            Line line = lines.get(node.lineId());
            if (station == null || !station.supported() || line == null) continue;
            supportedNodes.add(node.id());
            nodesByStationLine.put(List.of(node.stationId(), node.lineId()), node.id());
            Long previous = nodeByName.put(key(line.code(), StationNames.normalize(station.name())), node.id());
            if (previous != null) {
                throw new IllegalStateException("Two nodes share the timetable key " + line.code() + " " + station.name());
            }
        }
        Map<Long, Map<Long, Long>> rideEdges = new HashMap<>();
        int rideEdgeCount = 0;
        for (Edge edge : graph.edges()) {
            if (edge.type() != StepType.RIDE || !supportedNodes.contains(edge.fromNodeId()) || !supportedNodes.contains(edge.toNodeId())) continue;
            if (rideEdges.computeIfAbsent(edge.fromNodeId(), id -> new HashMap<>()).putIfAbsent(edge.toNodeId(), edge.id()) == null) {
                rideEdgeCount++;
            }
        }

        String header = reader.readLine();
        if (header == null || !HEADER.equals(header.replace("﻿", "").strip())) {
            throw new IllegalStateException("Train timetable header must be: " + HEADER);
        }
        int rows = 0;
        int express = 0;
        int unmapped = 0;
        Map<String, Integer> unmappedStations = new TreeMap<>();
        Map<TrainKey, List<Stop>> trains = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        String text;
        while ((text = reader.readLine()) != null) {
            if (text.isBlank()) continue;
            rows++;
            String[] f = text.split(",", -1);
            if (f.length != 10) throw new IllegalStateException("Train timetable row " + rows + " has " + f.length + " columns");
            String kind = f[4].strip();
            if (EXPRESS.equals(kind)) {
                express++;
                continue;
            }
            if (!LOCAL.equals(kind)) throw new IllegalStateException("Train timetable row " + rows + ": unknown express value " + kind);
            DayType dayType = DAY_TYPES.get(f[2].strip());
            if (dayType == null) throw new IllegalStateException("Train timetable row " + rows + ": unknown daytype " + f[2]);
            String lineCode = lineCode(f[0].strip());
            String station = f[1].strip();
            Long node = nodeByName.get(key(lineCode, StationNames.normalize(station)));
            if (node == null) {
                unmapped++;
                unmappedStations.merge(f[0].strip() + " " + station, 1, Integer::sum);
                continue;
            }
            int arrive;
            int depart;
            try {
                arrive = Integer.parseInt(f[6].strip());
                depart = Integer.parseInt(f[7].strip());
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("Train timetable row " + rows + ": arrive_sec/depart_sec must be integers");
            }
            TrainKey train = new TrainKey(dayType, intern(names, lineCode), intern(names, f[5].strip()),
                    intern(names, f[8].strip()), intern(names, f[9].strip()));
            trains.computeIfAbsent(train, k -> new ArrayList<>()).add(new Stop(node, intern(names, station), arrive, depart));
        }

        Map<Long, Map<DayType, List<Departure>>> rides = new HashMap<>();
        Map<String, Integer> pairsNotInGraph = new TreeMap<>();
        int rideCount = 0;
        int missingArrivals = 0;
        int invalid = 0;
        for (var entry : trains.entrySet()) {
            List<Stop> stops = entry.getValue();
            // A loop train (2호선 성수 -> 성수) passes some stations twice; departure order still gives the stop order.
            stops.sort(Comparator.comparingInt(Stop::departSec).thenComparingInt(Stop::arriveSec));
            for (int i = 1; i < stops.size(); i++) {
                Stop a = stops.get(i - 1);
                Stop b = stops.get(i);
                if (a.nodeId() == b.nodeId()) continue;
                int arrive = b.arriveSec();
                if (arrive <= 0) {
                    // One row (3호선 교대) has arrive_sec 0: the departure is the best known arrival.
                    arrive = b.departSec();
                    missingArrivals++;
                }
                if (arrive <= a.departSec()) {
                    invalid++;
                    continue;
                }
                Long edge = rideEdges.getOrDefault(a.nodeId(), Map.of()).get(b.nodeId());
                if (edge == null) {
                    pairsNotInGraph.merge(entry.getKey().lineCode() + "호선 " + a.station() + "->" + b.station(), 1, Integer::sum);
                    continue;
                }
                rides.computeIfAbsent(edge, id -> new EnumMap<>(DayType.class))
                        .computeIfAbsent(entry.getKey().dayType(), d -> new ArrayList<>())
                        .add(new Departure(a.departSec(), arrive, entry.getKey()));
                rideCount++;
            }
        }

        Map<Long, Map<DayType, Series>> series = new HashMap<>();
        long bytes = 0;
        for (var edge : rides.entrySet()) {
            Map<DayType, Series> byDay = new EnumMap<>(DayType.class);
            for (var day : edge.getValue().entrySet()) {
                Series s = new Series(day.getValue());
                byDay.put(day.getKey(), s);
                bytes += s.bytes();
            }
            series.put(edge.getKey(), Collections.unmodifiableMap(byDay));
        }
        // Train keys and their strings, roughly.
        bytes += trains.size() * 64L + names.size() * 64L;
        LoadReport report = new LoadReport(rows, express, unmapped, Collections.unmodifiableMap(unmappedStations), trains.size(),
                rideCount, missingArrivals, invalid, Collections.unmodifiableMap(pairsNotInGraph), rideEdgeCount, series.size(), bytes);
        Map<Long, Map<Long, Long>> frozenEdges = new HashMap<>();
        rideEdges.forEach((from, to) -> frozenEdges.put(from, Map.copyOf(to)));
        return new TrainTimetable(Map.copyOf(series), Map.copyOf(frozenEdges), Map.copyOf(nodesByStationLine), report);
    }

    /** First ride on the edge leaving at or after {@code atOrAfterSec}; empty after the last train or for unknown edges. */
    public Optional<Departure> nextDeparture(long edgeId, DayType dayType, int atOrAfterSec) {
        Series s = series(edgeId, dayType);
        return s == null ? Optional.empty() : s.next(atOrAfterSec);
    }

    /**
     * For planning backwards from an arrival time: among rides on the edge reaching B at or before
     * {@code arriveByOrBeforeSec}, the one leaving A latest. Empty when none arrives in time.
     */
    public Optional<Departure> lastDepartureArriving(long edgeId, DayType dayType, int arriveByOrBeforeSec) {
        Series s = series(edgeId, dayType);
        return s == null ? Optional.empty() : s.lastArriving(arriveByOrBeforeSec);
    }

    /** {@link #nextDeparture(long, DayType, int)} for boarding line {@code lineId} at a station towards the next station. */
    public Optional<Departure> nextDeparture(long lineId, long stationId, long nextStationId, DayType dayType, int atOrAfterSec) {
        OptionalLong edge = rideEdgeId(lineId, stationId, nextStationId);
        return edge.isEmpty() ? Optional.empty() : nextDeparture(edge.getAsLong(), dayType, atOrAfterSec);
    }

    /** {@link #lastDepartureArriving(long, DayType, int)} by line, station and next station. */
    public Optional<Departure> lastDepartureArriving(long lineId, long stationId, long nextStationId, DayType dayType,
                                                    int arriveByOrBeforeSec) {
        OptionalLong edge = rideEdgeId(lineId, stationId, nextStationId);
        return edge.isEmpty() ? Optional.empty() : lastDepartureArriving(edge.getAsLong(), dayType, arriveByOrBeforeSec);
    }

    /** The RIDE edge from a station to the next station on one line, or empty when they are not neighbours on it. */
    public OptionalLong rideEdgeId(long lineId, long stationId, long nextStationId) {
        Long from = nodesByStationLine.get(List.of(stationId, lineId));
        Long to = nodesByStationLine.get(List.of(nextStationId, lineId));
        if (from == null || to == null) return OptionalLong.empty();
        Long edge = rideEdges.getOrDefault(from, Map.of()).get(to);
        return edge == null ? OptionalLong.empty() : OptionalLong.of(edge);
    }

    /** All rides on the edge for the day type, by departure. */
    public List<Departure> departures(long edgeId, DayType dayType) {
        Series s = series(edgeId, dayType);
        if (s == null) return List.of();
        List<Departure> all = new ArrayList<>(s.depart.length);
        for (int i = 0; i < s.depart.length; i++) all.add(s.at(i));
        return all;
    }

    /** RIDE edges with at least one train. */
    public Set<Long> edgesWithTrains() {
        return series.keySet();
    }

    public LoadReport report() {
        return report;
    }

    private Series series(long edgeId, DayType dayType) {
        Map<DayType, Series> byDay = series.get(edgeId);
        return byDay == null || dayType == null ? null : byDay.get(dayType);
    }

    /** Index of the first value >= target in a sorted array (its length when none). */
    private static int firstAtLeast(int[] sorted, int target) {
        int low = 0;
        int high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid] < target) low = mid + 1; else high = mid;
        }
        return low;
    }

    /** "1호선" -> "1" (subway_lines.line_code). */
    static String lineCode(String line) {
        return line.endsWith("호선") ? line.substring(0, line.length() - 2) : line;
    }

    private static String key(String lineCode, String normalizedName) {
        return lineCode + "|" + normalizedName;
    }

    private static String intern(Map<String, String> names, String value) {
        return names.computeIfAbsent(value, v -> v);
    }
}
