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
        // TIME: st1 and st2 both take 10 minutes at most with a 10 minute spread, the lower id wins.
        assertThat(evaluation.best(Criterion.TIME).orElseThrow().stationId()).isEqualTo(1);
        assertThat(evaluation.best(Criterion.TIME).orElseThrow().maxMinutes()).isEqualTo(10);
        // FARE: st3 costs 600 + 600 = 1200, the rest 1500 or more.
        var fareBest = evaluation.best(Criterion.FARE).orElseThrow();
        assertThat(fareBest.stationId()).isEqualTo(3);
        assertThat(fareBest.totalFareWon()).isEqualTo(1200);
        // DISTANCE: st2 has the shortest total (5000 m); st1 is 1500 m longer, outside the 1 km tolerance.
        var distanceBest = evaluation.best(Criterion.DISTANCE).orElseThrow();
        assertThat(distanceBest.stationId()).isEqualTo(2);
        assertThat(distanceBest.totalDistanceM()).isEqualTo(5000);
    }

    @Test
    void aMemberWhoDoesNotTravelPaysNothingEvenIfTheCalculatorChargesTheBaseFare() {
        // like GraphFareCalculator: the base fare is charged even at distance 0
        Evaluation evaluation = engine(graph(), path -> 1550).evaluate(MEMBERS, NONE);
        var own1 = evaluation.candidates().get(1L);                    // st1 is A's own station: A pays 0, B pays 1550
        assertThat(own1.trips().get(0).fareWon()).isZero();
        assertThat(own1.trips().get(0).minutes()).isZero();
        assertThat(own1.trips().get(0).path().transferCount()).isZero();
        assertThat(own1.trips().get(1).fareWon()).isEqualTo(1550);
        assertThat(own1.totalFareWon()).isEqualTo(1550);
        assertThat(evaluation.candidates().get(2L).totalFareWon()).isEqualTo(1550);   // st2 is B's own station
        assertThat(evaluation.candidates().get(3L).totalFareWon()).isEqualTo(3100);   // both travel
        // so the FARE comparison is not distorted: the own stations (1550) beat the meeting in the middle (3100)
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().totalFareWon()).isEqualTo(1550);
    }

    @Test
    void aMemberWhoDoesNotTravelCountsAsZeroAgainstTheFareLimit() {
        // limit 1550 per person: at st1 A pays 0 and B pays 1550, both within it
        Evaluation evaluation = engine(graph(), path -> 1550).evaluate(MEMBERS, limits(null, null, 1550, null, null));
        assertThat(evaluation.candidates()).containsKeys(1L, 2L);
        assertThat(evaluation.candidates().get(1L).trips().get(0).fareWon()).isZero();
    }

    @Test
    void fareWithinToleranceIsATieDecidedByTime() {
        // st3 is 1200 won, st1 and st2 are 1250 (within 100 won of the cheapest) and st4 is far above.
        Evaluation evaluation = engine(graph(), path -> path.totalSeconds() == 0 ? 0 : (path.toStationId() == 3 ? 600 : 1250))
                .evaluate(MEMBERS, NONE);
        // st1 / st2: 0 + 1250 = 1250; st3: 1200; st4: 2500. Tie pool = st1, st2, st3 → TIME rule: st1 (max 10 min).
        assertThat(evaluation.best(Criterion.FARE).orElseThrow().stationId()).isEqualTo(1);
    }

    @Test
    void distanceWithinToleranceIsATieDecidedByTime() {
        Routes routes = new Routes()
                .route(1, 1, 0, 0, 0).route(1, 2, 600, 0, 5000).route(1, 3, 1200, 0, 5600)
                .route(2, 1, 600, 0, 5900).route(2, 2, 0, 0, 0).route(2, 3, 300, 0, 0);
        // st1: 5900, st2: 5000, st3: 5600 → shortest 5000, within 1000 m: all three. TIME: st3 (max 20), st1 (10), st2 (10).
        Evaluation evaluation = engine(routes, fare(1, 1)).evaluate(MEMBERS, NONE);
        assertThat(evaluation.best(Criterion.DISTANCE).orElseThrow().stationId()).isEqualTo(1);
    }

    @Test
    void onlyStationsEveryoneCanReachAreCandidates() {
        Routes routes = graph().route(1, 5, 300, 0, 1000);
        Evaluation evaluation = engine(routes, fare(600, 1500)).evaluate(MEMBERS, NONE);
        assertThat(evaluation.candidates()).containsOnlyKeys(1L, 2L, 3L, 4L);
    }

    @Test
    void everyConstraintFiltersTheCandidates() {
        var engine = engine(graph(), fare(600, 1500));
        // maxMinutes 20: st4 (25) drops. maxTransfers 0: st3 (one transfer) drops.
        assertThat(engine.evaluate(MEMBERS, limits(20, null, null, List.of(), List.of())).candidates()).containsOnlyKeys(1L, 2L, 3L);
        assertThat(engine.evaluate(MEMBERS, limits(null, 0, null, List.of(), List.of())).candidates()).containsOnlyKeys(1L, 2L, 4L);
        // maxFareWon is per person: 1000 keeps only st3 (600 each) and the stations where one person stays put.
        assertThat(engine.evaluate(MEMBERS, limits(null, null, 1000, List.of(), List.of())).candidates()).containsOnlyKeys(3L);
        assertThat(engine.evaluate(MEMBERS, limits(null, null, null, List.of(1L, 3L), List.of())).candidates()).containsOnlyKeys(2L, 4L);
        // 선호 (A) may spend 12 minutes at most: st3 (15) and st4 (25) drop; 인준 (B) is not limited.
        assertThat(engine.evaluate(MEMBERS, limits(null, null, null, List.of(), List.of(new MemberLimit(A, 12)))).candidates())
                .containsOnlyKeys(1L, 2L);
    }

    @Test
    void minutesRoundUp() {
        Routes routes = new Routes().route(1, 1, 0, 0, 0).route(1, 2, 601, 0, 100).route(2, 1, 60, 0, 100).route(2, 2, 0, 0, 0);
        Evaluation evaluation = engine(routes, fare(1, 1)).evaluate(MEMBERS, limits(10, null, null, List.of(), List.of()));
        // 601 s is 11 minutes, so st2 breaks the 10 minute limit for A; st1 (A 0 min, B 1 min) stays.
        assertThat(evaluation.candidates()).containsOnlyKeys(1L);
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
