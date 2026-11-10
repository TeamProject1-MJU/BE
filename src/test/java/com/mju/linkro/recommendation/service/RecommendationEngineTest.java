package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.dto.RecommendationResponse.MemberLimit;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.RecommendationEngine.Evaluation;
import com.mju.linkro.subway.api.FareCalculator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final List<Member> MEMBERS = List.of(new Member(A, "선호", 1), new Member(B, "인준", 2));
    static final AppliedConstraints NONE = AppliedConstraints.none();

    /** Fare is looked up by destination so each test can decide who is cheap. */
    static FareCalculator fare(int toThree, int elsewhere) {
        return path -> path.totalSeconds() == 0 ? 0 : (path.toStationId() == 3 ? toThree : elsewhere);
    }

    /**
     * Minutes (A, B): st1 (0, 10), st2 (10, 0), st3 (15, 20), st4 (25, 10).
     * Metres (A, B): st1 (0, 6500), st2 (5000, 0), st3 (3000, 4000), st4 (9000, 2000).
     */
    static Routes graph() {
        return new Routes()
                .route(1, 1, 0, 0, 0).route(1, 2, 600, 0, 5000).route(1, 3, 900, 1, 3000).route(1, 4, 1500, 0, 9000)
                .route(2, 1, 600, 0, 6500).route(2, 2, 0, 0, 0).route(2, 3, 1200, 1, 4000).route(2, 4, 600, 0, 2000);
    }

    static RecommendationEngine engine(Routes routes, FareCalculator fares) {
        return new RecommendationEngine(routes.finder(), fares, new RecommendationProperties());
    }

    static AppliedConstraints limits(Integer minutes, Integer transfers, Integer fare, List<Long> excluded, List<MemberLimit> members) {
        return new AppliedConstraints(minutes, transfers, fare, excluded, members);
    }

    @Test
    void callsShortestPathsFromOncePerParticipant() {
        Routes routes = graph();
        engine(routes, fare(600, 1500)).evaluate(MEMBERS, NONE);
        assertThat(routes.shortestPathsFromCalls).isEqualTo(2);
    }

    @Test
    void picksTheBestStationOfEachCriterion() {
        Evaluation evaluation = engine(graph(), fare(600, 1500)).evaluate(MEMBERS, NONE);
        // st1 and st2 are the members' departures, so only st3 and st4 remain.
        // TIME: st3 (max 20) beats st4 (max 25).
        assertThat(evaluation.best(Criterion.TIME).orElseThrow().stationId()).isEqualTo(3);
        assertThat(evaluation.best(Criterion.TIME).orElseThrow().maxMinutes()).isEqualTo(20);
        // FARE: st3 costs 600 + 600 = 1200, st4 is 1500 + 1500.
        var fareBest = evaluation.best(Criterion.FARE).orElseThrow();
        assertThat(fareBest.stationId()).isEqualTo(3);
        assertThat(fareBest.totalFareWon()).isEqualTo(1200);
        // DISTANCE: st3 is 7000 m, st4 is 11000 m (outside the 1 km tolerance).
        var distanceBest = evaluation.best(Criterion.DISTANCE).orElseThrow();
        assertThat(distanceBest.stationId()).isEqualTo(3);
        assertThat(distanceBest.totalDistanceM()).isEqualTo(7000);
    }

    @Test
    void memberDepartureStationsAreNotCandidates() {
        Evaluation evaluation = engine(graph(), path -> 1550).evaluate(MEMBERS, NONE);
        assertThat(evaluation.candidates()).containsOnlyKeys(3L, 4L);
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().stationId()).isEqualTo(3);
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().totalFareWon()).isEqualTo(3100);
        assertThat(evaluation.candidates().values()).allSatisfy(candidate ->
                assertThat(candidate.trips()).allSatisfy(trip -> {
                    assertThat(trip.minutes()).isPositive();
                    assertThat(trip.fareWon()).isEqualTo(1550);
                }));
    }

    @Test
    void excludingDeparturesCanLeaveNoCandidate() {
        // both members start at st1, and st1 is the only reachable station
        List<Member> same = List.of(new Member(A, "선호", 1), new Member(B, "인준", 1));
        Routes routes = new Routes().route(1, 1, 0, 0, 0);
        Evaluation evaluation = engine(routes, path -> 1550).evaluate(same, NONE);
        assertThat(evaluation.candidates()).isEmpty();
        for (Criterion criterion : Criterion.values()) {
            assertThat(evaluation.best(criterion)).isEmpty();
        }
    }

    @Test
    void fareWithinToleranceIsATieDecidedByTime() {
        // departures st1/st2 are out. st4 is cheapest at 1000, st3 is 1100 (within 100 won).
        Evaluation evaluation = engine(graph(), path -> path.toStationId() == 3 ? 550 : 500).evaluate(MEMBERS, NONE);
        // TIME rule among the tie pool picks st3 (max 20) over st4 (max 25).
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().stationId()).isEqualTo(3);
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().totalFareWon()).isEqualTo(1100);
    }

    @Test
    void distanceWithinToleranceIsATieDecidedByTime() {
        Routes routes = new Routes()
                .route(1, 1, 0, 0, 0).route(1, 2, 600, 0, 5000).route(1, 3, 1200, 0, 5600).route(1, 4, 1500, 0, 2000)
                .route(2, 1, 600, 0, 5900).route(2, 2, 0, 0, 0).route(2, 3, 300, 0, 400).route(2, 4, 1800, 0, 3900);
        // departures st1/st2 are out. st3: 6000 m, st4: 5900 m → shortest 5900, both within 1000 m.
        // TIME: st3 (max 20) beats st4 (max 30).
        Evaluation evaluation = engine(routes, fare(1, 1)).evaluate(MEMBERS, NONE);
        assertThat(evaluation.best(Criterion.DISTANCE).orElseThrow().stationId()).isEqualTo(3);
    }

    @Test
    void onlyStationsEveryoneCanReachAreCandidates() {
        Routes routes = graph().route(1, 5, 300, 0, 1000);
        Evaluation evaluation = engine(routes, fare(600, 1500)).evaluate(MEMBERS, NONE);
        // st5 is only reachable from A; st1 and st2 are departures.
        assertThat(evaluation.candidates()).containsOnlyKeys(3L, 4L);
    }

    @Test
    void everyConstraintFiltersTheCandidates() {
        var engine = engine(graph(), fare(600, 1500));
        // departures st1/st2 are already out. maxMinutes 20: st4 (25) drops.
        assertThat(engine.evaluate(MEMBERS, limits(20, null, null, List.of(), List.of())).candidates()).containsOnlyKeys(3L);
        // maxTransfers 0: st3 (one transfer) drops.
        assertThat(engine.evaluate(MEMBERS, limits(null, 0, null, List.of(), List.of())).candidates()).containsOnlyKeys(4L);
        // maxFareWon is per person: 1000 keeps only st3 (600 each).
        assertThat(engine.evaluate(MEMBERS, limits(null, null, 1000, List.of(), List.of())).candidates()).containsOnlyKeys(3L);
        // user excludeStationIds still apply on top of departure exclusion: st3 is also dropped, only st4 remains.
        assertThat(engine.evaluate(MEMBERS, limits(null, null, null, List.of(3L), List.of())).candidates()).containsOnlyKeys(4L);
        // 선호 (A) may spend 16 minutes at most: st3 (15) stays, st4 (25) drops.
        assertThat(engine.evaluate(MEMBERS, limits(null, null, null, List.of(), List.of(new MemberLimit(A, 16)))).candidates())
                .containsOnlyKeys(3L);
    }

    @Test
    void departureExclusionIsSeparateFromUserExcludeStationIds() {
        Evaluation evaluation = engine(graph(), fare(600, 1500))
                .evaluate(MEMBERS, limits(null, null, null, List.of(4L), List.of()));
        // only the user-excluded id was passed in; st1/st2 are gone without being listed there.
        assertThat(evaluation.candidates()).containsOnlyKeys(3L);
    }

    @Test
    void minutesRoundUp() {
        Routes routes = new Routes()
                .route(1, 1, 0, 0, 0).route(1, 2, 601, 0, 100).route(1, 3, 600, 0, 100)
                .route(2, 1, 60, 0, 100).route(2, 2, 0, 0, 0).route(2, 3, 60, 0, 100);
        Evaluation evaluation = engine(routes, fare(1, 1)).evaluate(MEMBERS, limits(10, null, null, List.of(), List.of()));
        // 601 s is 11 minutes, so st2 breaks the 10 minute limit for A; st1 is a departure; st3 (600 s = 10 min) stays.
        assertThat(evaluation.candidates()).containsOnlyKeys(3L);
    }

    @Test
    void noCandidateGivesNoBestStation() {
        Evaluation evaluation = engine(graph(), fare(600, 1500)).evaluate(MEMBERS, limits(1, null, null, List.of(), List.of()));
        assertThat(evaluation.candidates()).isEmpty();
        for (Criterion criterion : Criterion.values()) {
            assertThat(evaluation.best(criterion)).isEmpty();
        }
    }
}
