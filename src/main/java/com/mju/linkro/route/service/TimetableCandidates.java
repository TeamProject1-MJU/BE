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
 *   <li>The search stops when it finds no route, when a route arrives more than the cut (default {@link #DEFAULT_CUT},
 *       {@code linkro.route.timetable-candidate-cut-minutes}) after the first (that
 *       route is left out), when a route adds no transfer station to block (it is kept when new), or at {@code max}
 *       routes when one more would be added ({@link Result#limitReached}).</li>
 *   <li>Two routes are the same when they have the same line sequence and the same set of transfer stations; a change of
 *       trains on the same line (성수) is not in the line sequence. A repeated route is left out.</li>
 *   <li>A route other than the first is removed when another route (the first included) has fewer transfers and
 *       arrives more than the grace (default {@link #DEFAULT_GRACE}, {@code linkro.route.timetable-candidate-grace-minutes},
 *       #155) earlier. Arriving exactly the grace later keeps it, so a route with more transfers but the same arrival as
 *       one with fewer stays (창동 -> 잠실: 노원·건대입구 next to 동대문역사문화공원). Only more transfers remove a route:
 *       of two routes with as many transfers, the later one stays (no general Pareto filter). The first route is never
 *       removed. This runs after the search, so the removed routes still blocked their transfer stations and counted
 *       towards {@code max}.</li>
 *   <li>After the first route, routes are ordered by arrival, then transfers.</li>
 * </ul>
 */
final class TimetableCandidates {
    /** Routes arriving more than this after the first route are left out, unless configured otherwise. */
    static final Duration DEFAULT_CUT = Duration.ofMinutes(15);
    /**
     * A route with more transfers than another is removed only when it arrives more than this later (#155). 6 minutes:
     * against 카카오맵 on five routes at four request times it kept every route 카카오맵 showed and added as few others as
     * 5 minutes (7 kept six more 2-3 transfer routes 6.5-7 minutes later).
     */
    static final Duration DEFAULT_GRACE = Duration.ofMinutes(6);

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
     * @param removed      routes removed for more transfers and arriving more than the grace later than another
     */
    record Result(List<TimedRoute> routes, boolean limitReached, int duplicates, boolean cut, int removed) {}

    private TimetableCandidates() {}

    /** {@link #of(TimedRoute, Search, int, Duration, Duration)} with the default cut and grace. */
    static Result of(TimedRoute first, Search search, int max) {
        return of(first, search, max, DEFAULT_CUT, DEFAULT_GRACE);
    }

    /**
     * @param cut   routes arriving more than this after the first are left out and end the search; positive
     * @param grace a route with more transfers than another is removed only when it arrives more than this later; not
     *              negative
     */
    static Result of(TimedRoute first, Search search, int max, Duration cut, Duration grace) {
        if (max < 1) throw new IllegalArgumentException("max must be positive: " + max);
        if (cut.isNegative() || cut.isZero()) throw new IllegalArgumentException("cut must be positive: " + cut);
        if (grace.isNegative()) throw new IllegalArgumentException("grace must not be negative: " + grace);
        Instant latest = first.arriveAt().plus(cut);
        List<TimedRoute> found = new ArrayList<>(List.of(first));
        Set<List<Object>> seen = new HashSet<>(List.of(key(first)));
        Set<Long> blocked = new LinkedHashSet<>(transferStations(first));
        boolean limitReached = false;
        boolean cutOff = false;
        int duplicates = 0;
        while (!blocked.isEmpty()) {
            Optional<TimedRoute> next = search.avoiding(Set.copyOf(blocked));
            if (next.isEmpty()) break;
            TimedRoute route = next.get();
            if (route.arriveAt().isAfter(latest)) {
                cutOff = true;
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
        List<TimedRoute> others = new ArrayList<>();
        for (TimedRoute route : found.subList(1, found.size())) {
            if (found.stream().noneMatch(other -> outclassedBy(other, route, grace))) others.add(route);
        }
        int removed = found.size() - 1 - others.size();
        others.sort(Comparator.comparing(TimedRoute::arriveAt).thenComparingInt(route -> route.path().transferCount()));
        List<TimedRoute> ordered = new ArrayList<>(List.of(first));
        ordered.addAll(others);
        return new Result(List.copyOf(ordered), limitReached, duplicates, cutOff, removed);
    }

    /** {@code other} needs fewer transfers than {@code route}, and {@code route} arrives more than {@code grace} later. */
    static boolean outclassedBy(TimedRoute other, TimedRoute route, Duration grace) {
        return other.path().transferCount() < route.path().transferCount() && route.arriveAt().isAfter(other.arriveAt().plus(grace));
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
