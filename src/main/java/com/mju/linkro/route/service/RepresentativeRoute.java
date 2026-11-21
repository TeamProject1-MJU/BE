package com.mju.linkro.route.service;

import com.mju.linkro.subway.api.TimedRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The first route of a plain search (#190). {@link TimetableCandidates} keeps the earliest arrival first; when another
 * candidate needs fewer transfers and takes at most {@code capSeconds} longer from leaving to arriving
 * ({@code linkro.subway.graph.timetable-detour-cap-seconds}, the #185 cap: exactly the cap still counts), that one goes
 * first instead. Among several: the fewest transfers, then the shortest travel time, then the earliest arrival. The rest
 * keep the candidates' order (arrival, then transfers), the former first route among them.
 */
final class RepresentativeRoute {
    private RepresentativeRoute() {}

    /** Index in {@code routes} of the route to put first: 0 when none qualifies. */
    static int pick(List<TimedRoute> routes, int capSeconds) {
        if (routes.isEmpty()) return 0;
        TimedRoute fastest = routes.get(0);
        long base = travelSeconds(fastest);
        int best = 0;
        for (int i = 1; i < routes.size(); i++) {
            TimedRoute route = routes.get(i);
            if (route.path().transferCount() >= fastest.path().transferCount()) continue;
            if (travelSeconds(route) - base > capSeconds) continue;
            if (best == 0 || BETTER.compare(route, routes.get(best)) < 0) best = i;
        }
        return best;
    }

    /** {@code items} (the routes' order) with the {@link #pick picked} one first and the others by arrival, then transfers. */
    static <T> List<T> reorder(List<T> items, List<TimedRoute> routes, int capSeconds) {
        int picked = pick(routes, capSeconds);
        if (picked == 0) return items;
        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (i != picked) rest.add(i);
        // Stable: the candidates are already by arrival then transfers; the former first route joins them in that order.
        rest.sort(Comparator.comparing((Integer i) -> routes.get(i).arriveAt()).thenComparingInt(i -> routes.get(i).path().transferCount()));
        List<T> result = new ArrayList<>(List.of(items.get(picked)));
        rest.forEach(i -> result.add(items.get(i)));
        return result;
    }

    static long travelSeconds(TimedRoute route) {
        return Duration.between(route.leaveAt(), route.arriveAt()).toSeconds();
    }

    private static final Comparator<TimedRoute> BETTER = Comparator.comparingInt((TimedRoute route) -> route.path().transferCount())
            .thenComparingLong(RepresentativeRoute::travelSeconds)
            .thenComparing(TimedRoute::arriveAt);
}
