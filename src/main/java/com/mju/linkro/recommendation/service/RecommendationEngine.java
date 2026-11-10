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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Candidate set and best station per criterion (API 8.1). One {@code shortestPathsFrom} per participant, one
 * {@code fareOf} per route; everything else is arithmetic. It holds no state between calls and touches no database.
 */
@Component
public class RecommendationEngine {
    private final SubwayRouteFinder finder;
    private final FareCalculator fares;
    private final RecommendationProperties properties;

    public RecommendationEngine(SubwayRouteFinder finder, FareCalculator fares, RecommendationProperties properties) {
        this.finder = finder;
        this.fares = fares;
        this.properties = properties;
    }

    /**
     * Candidates by station id, already filtered by the constraints. A member's departure station is never a candidate.
     * Empty when nothing qualifies.
     */
    public record Evaluation(Map<Long, Candidate> candidates, RecommendationProperties properties) {

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

    /**
     * @throws com.mju.linkro.common.exception.BusinessException STATION_NOT_FOUND / STATION_UNSUPPORTED from a
     *         participant's departure station
     */
    public Evaluation evaluate(List<Member> members, AppliedConstraints constraints) {
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

        Map<UUID, Integer> personalLimits = new HashMap<>();
        for (MemberLimit limit : constraints.memberLimits()) {
            personalLimits.put(limit.userId(), limit.maxMinutes());
        }
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
            Member member = members.get(i);
            int minutes = minutes(path.get().totalSeconds());
            int fare = fares.fareOf(path.get());
            if (exceeds(constraints.maxMinutes(), minutes) || exceeds(constraints.maxTransfers(), path.get().transferCount())
                    || exceeds(constraints.maxFareWon(), fare) || exceeds(personalLimits.get(member.userId()), minutes)) {
                return null;
            }
            trips.add(new Trip(member, path.get(), minutes, fare));
        }
        return trips;
    }

    private static boolean exceeds(Integer limit, int value) {
        return limit != null && value > limit;
    }

    static int minutes(int seconds) {
        return (seconds + 59) / 60;
    }
}
