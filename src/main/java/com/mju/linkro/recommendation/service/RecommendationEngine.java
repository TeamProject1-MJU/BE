package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.dto.RecommendationResponse.MemberLimit;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.Candidate.Trip;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.RoutePath;
import com.mju.linkro.subway.api.ShortestPathTree;
import com.mju.linkro.subway.api.SubwayRouteFinder;
import com.mju.linkro.subway.api.TimedRoute;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.api.TimetableRouteFinder.TimedTree;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Candidate set and best station per criterion (API 8.1). It holds no state between calls and touches no database.
 *
 * <p>With the timetable (#96) every member's time comes from real trains: with an appointment, one latest-departure
 * search per candidate gives the last moment each member can leave and still arrive by the appointment
 * (minutes = appointment − leaving); without one, one earliest-arrival search per member from the request time gives
 * minutes = arrival − leaving for the first train. A member who cannot arrive in time on that service day's trains
 * removes the candidate; nothing falls back to fixed costs. Only when there is no timetable (module mock,
 * {@code linkro.subway.timetable.enabled=false}) or it has no train for that day type is the fixed-cost graph used
 * ({@code shortestPathsFrom}, every member ESTIMATE, one WARN per request).
 */
@Slf4j
@Component
public class RecommendationEngine {
    public static final String TIMETABLE = "TIMETABLE";
    public static final String ESTIMATE = "ESTIMATE";

    private final SubwayRouteFinder finder;
    private final FareCalculator fares;
    private final RecommendationProperties properties;
    private final TimetableRouteFinder timetable;

    @Autowired
    public RecommendationEngine(SubwayRouteFinder finder, FareCalculator fares, RecommendationProperties properties,
                                Optional<TimetableRouteFinder> timetable) {
        this.finder = finder;
        this.fares = fares;
        this.properties = properties;
        this.timetable = timetable.orElse(null);
    }

    /** Fixed-cost engine without a timetable. */
    public RecommendationEngine(SubwayRouteFinder finder, FareCalculator fares, RecommendationProperties properties) {
        this(finder, fares, properties, Optional.empty());
    }

    /**
     * Candidates by station id, already filtered by the constraints. A member's departure station is never a candidate.
     * Empty when nothing qualifies.
     *
     * @param source              TIMETABLE or ESTIMATE for every trip of this evaluation
     * @param unreachableByTrain  stations dropped because a member cannot arrive in time on the timetable's trains
     * @param fallbackMembers     members whose times came from fixed costs (0 in timetable mode)
     */
    public record Evaluation(Map<Long, Candidate> candidates, RecommendationProperties properties, String source,
                             int unreachableByTrain, int fallbackMembers) {

        public Evaluation(Map<Long, Candidate> candidates, RecommendationProperties properties) {
            this(candidates, properties, ESTIMATE, 0, 0);
        }

        /** TIME: slowest person, then spread (max − min), then total minutes (same order as the average). */
        static final Comparator<Candidate> BY_TIME = Comparator.comparingInt(Candidate::maxMinutes)
                .thenComparingInt(Candidate::spreadMinutes).thenComparingInt(Candidate::sumMinutes)
                .thenComparingLong(Candidate::stationId);

        public Optional<Candidate> best(Criterion criterion) {
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            return switch (criterion) {
                case TIME -> candidates.values().stream().min(BY_TIME);
                case FARE -> withinTolerance(Comparator.comparingInt(Candidate::totalFareWon),
                        Candidate::totalFareWon, properties.getFareToleranceWon());
                case DISTANCE -> withinTolerance(Comparator.comparingInt(Candidate::totalDistanceM),
                        Candidate::totalDistanceM, properties.getDistanceToleranceM());
            };
        }

        /** The cheapest/shortest plus the tolerance counts as a tie; the TIME rule picks among the tied. */
        private Optional<Candidate> withinTolerance(Comparator<Candidate> order,
                java.util.function.ToIntFunction<Candidate> metric, int tolerance) {
            int lowest = metric.applyAsInt(candidates.values().stream().min(order).orElseThrow());
            return candidates.values().stream().filter(candidate -> metric.applyAsInt(candidate) <= lowest + tolerance)
                    .min(BY_TIME);
        }
    }

    /** Without an appointment, leaving now. */
    public Evaluation evaluate(List<Member> members, AppliedConstraints constraints) {
        return evaluate(members, constraints, null, Instant.now());
    }

    /**
     * @param appointmentAt the room's appointment, or null to leave at {@code now}
     * @throws com.mju.linkro.common.exception.BusinessException STATION_NOT_FOUND / STATION_UNSUPPORTED from a
     *         participant's departure station
     */
    public Evaluation evaluate(List<Member> members, AppliedConstraints constraints, Instant appointmentAt, Instant now) {
        long started = System.nanoTime();
        Instant reference = appointmentAt != null ? appointmentAt : now;
        Evaluation evaluation;
        String fallbackReason = null;
        if (timetable == null) {
            fallbackReason = "no timetable (linkro.subway.module=mock or linkro.subway.timetable.enabled=false)";
        } else if (!timetable.covers(reference)) {
            fallbackReason = "the timetable has no train for the service day of " + reference;
        }
        if (fallbackReason == null) {
            evaluation = timed(members, constraints, appointmentAt, now);
        } else {
            Evaluation estimated = estimated(members, constraints);
            evaluation = new Evaluation(estimated.candidates(), properties, ESTIMATE, 0, members.size());
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        log.info("Recommendation evaluated ({}): {} members, {} candidates, {} unreachable by train in {} ms",
                evaluation.source(), members.size(), evaluation.candidates().size(), evaluation.unreachableByTrain(), millis);
        if (evaluation.fallbackMembers() > 0) {
            log.warn("Recommendation used fixed-cost estimates for {} member(s): {}", evaluation.fallbackMembers(), fallbackReason);
        }
        return evaluation;
    }

    // ---- timetable ----

    private Evaluation timed(List<Member> members, AppliedConstraints constraints, Instant appointmentAt, Instant now) {
        members.forEach(member -> timetable.requireStation(member.departureStationId()));
        Set<Long> stations = new TreeSet<>(timetable.stationIds());
        stations.removeAll(constraints.excludeStationIds());
        members.forEach(member -> stations.remove(member.departureStationId()));
        Set<Long> origins = new LinkedHashSet<>();
        members.forEach(member -> origins.add(member.departureStationId()));
        Map<UUID, Integer> personalLimits = personalLimits(constraints);

        Map<Long, TimedTree> trees = new HashMap<>();
        if (appointmentAt == null) {
            origins.forEach(origin -> trees.put(origin, timetable.departingAt(origin, now)));
        }
        // One search per candidate; they share nothing, so they run in parallel and are read back in station order.
        Map<Long, Map<Long, TimedRoute>> routesByStation = new ConcurrentHashMap<>();
        stations.parallelStream().forEach(stationId -> {
            Map<Long, TimedRoute> routes;
            if (appointmentAt != null) {
                routes = timetable.latestDepartures(stationId, appointmentAt, origins, 0);
            } else {
                routes = new HashMap<>();
                for (long origin : origins) {
                    Optional<TimedRoute> route = trees.get(origin).routeTo(stationId);
                    if (route.isPresent()) routes.put(origin, route.get());
                }
            }
            routesByStation.put(stationId, routes);
        });
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        int unreachable = 0;
        for (long stationId : stations) {
            Map<Long, TimedRoute> routes = routesByStation.get(stationId);
            if (!routes.keySet().containsAll(origins)) {
                unreachable++;
                continue;
            }
            List<Trip> trips = new ArrayList<>(members.size());
            for (Member member : members) {
                TimedRoute route = routes.get(member.departureStationId());
                Instant end = appointmentAt != null ? appointmentAt : route.arriveAt();
                int minutes = minutes((int) Duration.between(route.leaveAt(), end).toSeconds());
                Trip trip = trip(member, route.path(), minutes, route, TIMETABLE, constraints, personalLimits);
                if (trip == null) {
                    trips = null;
                    break;
                }
                trips.add(trip);
            }
            if (trips != null) {
                candidates.put(stationId, new Candidate(stationId, trips));
            }
        }
        return new Evaluation(candidates, properties, TIMETABLE, unreachable, 0);
    }

    // ---- fixed costs ----

    private Evaluation estimated(List<Member> members, AppliedConstraints constraints) {
        List<ShortestPathTree> trees = new ArrayList<>(members.size());
        for (Member member : members) {
            trees.add(finder.shortestPathsFrom(member.departureStationId()));
        }
        Set<Long> common = new TreeSet<>(trees.get(0).reachableStationIds());
        for (ShortestPathTree tree : trees.subList(1, trees.size())) {
            common.retainAll(tree.reachableStationIds());
        }
        common.removeAll(constraints.excludeStationIds());
        // Meeting at someone's own departure station is not a middle station; this is not a user condition, so it is
        // kept out of appliedConstraints.excludeStationIds.
        members.forEach(member -> common.remove(member.departureStationId()));

        Map<UUID, Integer> personalLimits = personalLimits(constraints);
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        for (long stationId : common) {
            List<Trip> trips = trips(members, trees, stationId, constraints, personalLimits);
            if (trips != null) {
                candidates.put(stationId, new Candidate(stationId, trips));
            }
        }
        return new Evaluation(candidates, properties);
    }

    /** Null when the station is unreachable for someone or breaks a constraint. */
    private List<Trip> trips(List<Member> members, List<ShortestPathTree> trees, long stationId,
                             AppliedConstraints constraints, Map<UUID, Integer> personalLimits) {
        List<Trip> trips = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            Optional<RoutePath> path = trees.get(i).pathTo(stationId);
            if (path.isEmpty()) {
                return null;
            }
            Trip trip = trip(members.get(i), path.get(), minutes(path.get().totalSeconds()), null, ESTIMATE, constraints, personalLimits);
            if (trip == null) {
                return null;
            }
            trips.add(trip);
        }
        return trips;
    }

    /** The trip, or null when it breaks a constraint (the same checks for both sources). */
    private Trip trip(Member member, RoutePath path, int minutes, TimedRoute timed, String source,
                      AppliedConstraints constraints, Map<UUID, Integer> personalLimits) {
        int fare = fares.fareOf(path);
        if (exceeds(constraints.maxMinutes(), minutes) || exceeds(constraints.maxTransfers(), path.transferCount())
                || exceeds(constraints.maxFareWon(), fare) || exceeds(personalLimits.get(member.userId()), minutes)) {
            return null;
        }
        return new Trip(member, path, minutes, fare, timed, source);
    }

    private static Map<UUID, Integer> personalLimits(AppliedConstraints constraints) {
        Map<UUID, Integer> personalLimits = new HashMap<>();
        for (MemberLimit limit : constraints.memberLimits()) {
            personalLimits.put(limit.userId(), limit.maxMinutes());
        }
        return personalLimits;
    }

    private static boolean exceeds(Integer limit, int value) {
        return limit != null && value > limit;
    }

    static int minutes(int seconds) {
        return (seconds + 59) / 60;
    }
}
