package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.PathStep;
import com.mju.linkro.subway.api.StepType;
import com.mju.linkro.subway.api.TimedLeg;
import com.mju.linkro.subway.api.TimedRoute;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Alternatives of a plain search timetable route (#153). The first route is the one the plain search always gave (the
 * recommendation's route without an appointment) and stays first. The others come from searching again without changing
 * lines at the transfer stations of every route found so far, the blocked set growing each time:
 *
 * <ul>
 *   <li>The search stops when it finds no route, when a route arrives more than {@link #CUT} after the first (that
 *       route is left out), when a route adds no transfer station to block (it is kept when new), or at {@code max}
 *       routes when one more would be added ({@link Result#limitReached}).</li>
 *   <li>Two routes are the same when they have the same line sequence and the same set of transfer stations; a change of
 *       trains on the same line (성수) is not in the line sequence. A repeated route is left out.</li>
 *   <li>After the first route, routes are ordered by arrival, then transfers.</li>
 * </ul>
 */
final class TimetableCandidates {
    /** Routes arriving more than this after the first route are left out. */
    static final Duration CUT = Duration.ofMinutes(15);

    /** The search of one more route without changing lines at the given stations. */
    @FunctionalInterface
    interface Search {
        Optional<TimedRoute> avoiding(Set<Long> transferStations);
    }

    /**
     * @param routes       the first route, then the others in order
     * @param limitReached one more route would have been added past {@code max}
     * @param duplicates   routes left out as repeats
     * @param cut          a route was left out for arriving too late (and the search stopped)
     */
    record Result(List<TimedRoute> routes, boolean limitReached, int duplicates, boolean cut) {}

    private TimetableCandidates() {}

    static Result of(TimedRoute first, Search search, int max) {
        if (max < 1) throw new IllegalArgumentException("max must be positive: " + max);
        Instant latest = first.arriveAt().plus(CUT);
        List<TimedRoute> found = new ArrayList<>(List.of(first));
        Set<List<Object>> seen = new HashSet<>(List.of(key(first)));
        Set<Long> blocked = new LinkedHashSet<>(transferStations(first));
        boolean limitReached = false;
        boolean cut = false;
        int duplicates = 0;
        while (!blocked.isEmpty()) {
            Optional<TimedRoute> next = search.avoiding(Set.copyOf(blocked));
            if (next.isEmpty()) break;
            TimedRoute route = next.get();
            if (route.arriveAt().isAfter(latest)) {
                cut = true;
                break;
            }
            boolean grew = blocked.addAll(transferStations(route));
            if (!seen.add(key(route))) {
                duplicates++;
            } else if (found.size() >= max) {
                limitReached = true;
                break;
            } else {
                found.add(route);
            }
            if (!grew) break;
        }
        List<TimedRoute> others = new ArrayList<>(found.subList(1, found.size()));
        others.sort(Comparator.comparing(TimedRoute::arriveAt).thenComparingInt(route -> route.path().transferCount()));
        List<TimedRoute> ordered = new ArrayList<>(List.of(first));
        ordered.addAll(others);
        return new Result(List.copyOf(ordered), limitReached, duplicates, cut);
    }

    /** Stations of the TRANSFER steps (walks between lines and branch changes). */
    static Set<Long> transferStations(TimedRoute route) {
        Set<Long> stations = new LinkedHashSet<>();
        for (PathStep step : route.path().steps()) {
            if (step.type() == StepType.TRANSFER) stations.add(step.fromStationId());
        }
        return stations;
    }

    /** Line sequence (one entry per run of the same line) and the set of transfer stations. */
    static List<Object> key(TimedRoute route) {
        List<Long> lines = new ArrayList<>();
        for (TimedLeg leg : route.legs()) {
            if (lines.isEmpty() || lines.get(lines.size() - 1) != leg.lineId()) lines.add(leg.lineId());
        }
        return List.of(lines, transferStations(route));
    }
}
