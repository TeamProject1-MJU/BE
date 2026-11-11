package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.Candidate.Trip;
import com.mju.linkro.recommendation.service.RecommendationEngine.Evaluation;
import com.mju.linkro.subway.api.FareCalculator;
import com.mju.linkro.subway.api.TimetableRouteFinder;
import com.mju.linkro.subway.graph.DijkstraSubwayRouteFinder;
import com.mju.linkro.subway.graph.SubwayGraph;
import com.mju.linkro.subway.graph.SubwayGraphProperties;
import com.mju.linkro.subway.graph.TimetableRouterTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mju.linkro.subway.graph.TimetableRouterTest.THURSDAY;
import static com.mju.linkro.subway.graph.TimetableRouterTest.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine on the hand-made timetable of {@link TimetableRouterTest} (#96): members at 가(1) and 마(5) on the 2호선
 * loop. Only R1 (마 32100 -> 가 32200 -> 나 32290 -> 다 32390) leaves 마 on Thursday.
 */
class RecommendationEngineTimetableTest {
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final List<Member> MEMBERS = List.of(new Member(A, "가역", 1), new Member(B, "마역", 5));
    static final FareCalculator FARE = path -> 1550;

    static RecommendationEngine engine(boolean timetable) {
        SubwayGraph graph = SubwayGraph.of(TimetableRouterTest.DATA);
        SubwayGraphProperties costs = new SubwayGraphProperties();
        Optional<TimetableRouteFinder> router = timetable ? Optional.of(TimetableRouterTest.router()) : Optional.empty();
        return new RecommendationEngine(new DijkstraSubwayRouteFinder(graph, costs), FARE, new RecommendationProperties(), router);
    }

    @Test
    void appointmentTimesComeFromTrainsAndUnreachableStationsAreLeftOut() {
        Evaluation evaluation = engine(true).evaluate(MEMBERS, AppliedConstraints.none(), at(THURSDAY, 32390), Instant.EPOCH);
        assertThat(evaluation.source()).isEqualTo(RecommendationEngine.TIMETABLE);
        assertThat(evaluation.fallbackMembers()).isZero();
        // 나 and 다 are reachable by 32390 on R1; from 마 nothing reaches 라 or the 5호선 stations in time.
        assertThat(evaluation.candidates()).containsOnlyKeys(2L, 3L);
        assertThat(evaluation.unreachableByTrain()).isEqualTo(5);
        Candidate da = evaluation.candidates().get(3L);
        assertThat(da.trips()).allSatisfy(trip -> {
            assertThat(trip.source()).isEqualTo(RecommendationEngine.TIMETABLE);
            assertThat(trip.timed()).isNotNull();
        });
        // Minutes = appointment − leaving: 가 leaves at 32200 (190 s), 마 at 32100 (290 s).
        assertThat(da.trips()).extracting(Trip::minutes).containsExactly(4, 5);
        assertThat(da.trips().get(0).timed().legs().get(0).trainCode()).isEqualTo("R1");
    }

    @Test
    void maxMinutesFiltersOnTimetableMinutes() {
        AppliedConstraints fourMinutes = new AppliedConstraints(4, null, null, List.of(), List.of());
        Evaluation evaluation = engine(true).evaluate(MEMBERS, fourMinutes, at(THURSDAY, 32390), Instant.EPOCH);
        // Minutes are leaving -> arrival, without the wait for the appointment. 나 (arrival 32290): 가 90 s, 마 190 s
        // (2 and 4 min). 다 (32390): 마 leaves 32100, 290 s (5 min).
        assertThat(evaluation.candidates()).containsOnlyKeys(2L);
        assertThat(evaluation.candidates().get(2L).trips()).extracting(Trip::minutes).containsExactly(2, 4);
        assertThat(evaluation.unreachableByTrain()).isEqualTo(5);
    }

    @Test
    void beforeTheFirstTrainEveryStationIsUnreachable() {
        // Friday 04:00 belongs to Friday's service day, whose first train is later.
        Evaluation evaluation = engine(true).evaluate(MEMBERS, AppliedConstraints.none(), at(THURSDAY.plusDays(1), 14400), Instant.EPOCH);
        assertThat(evaluation.source()).isEqualTo(RecommendationEngine.TIMETABLE);
        assertThat(evaluation.candidates()).isEmpty();
        assertThat(evaluation.unreachableByTrain()).isEqualTo(7);
        assertThat(evaluation.fallbackMembers()).isZero();
    }

    @Test
    void noServiceMeansNoTrainArrivesInTheHourBefore() {
        RecommendationEngine engine = engine(true);
        // Thursday's last train arrives at Friday 00:03:10; Friday's first arrives at 08:21:30.
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 3600))).isFalse();        // Friday 01:00
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 3600 + 190))).isFalse();  // 00:03:10 is exactly 60 min before
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 3600 + 191))).isTrue();
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 14400))).isTrue();        // Friday 04:00
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 30089))).isTrue();        // 1 s before the first arrival
        assertThat(engine.noServiceBefore(at(THURSDAY.plusDays(1), 30090))).isFalse();
        assertThat(engine.noServiceBefore(at(THURSDAY, 32390))).isFalse();
        assertThat(engine.noServiceBefore(null)).isFalse();
        // Without the timetable nothing is refused.
        assertThat(engine(false).noServiceBefore(at(THURSDAY.plusDays(1), 14400))).isFalse();
    }

    @Test
    void withoutAnAppointmentMembersLeaveForTheNextTrain() {
        Evaluation evaluation = engine(true).evaluate(MEMBERS, AppliedConstraints.none(), null, at(THURSDAY, 30500));
        assertThat(evaluation.source()).isEqualTo(RecommendationEngine.TIMETABLE);
        // 가 takes L2 (30600 -> 다 30810, 4 min); 마 waits for R1 (32100 -> 다 32390, 5 min).
        assertThat(evaluation.candidates().get(3L).trips()).extracting(Trip::minutes).containsExactly(4, 5);
    }

    @Test
    void aDayWithoutTrainsFallsBackForEveryMember() {
        // The hand-made timetable only has weekday trains: Saturday is ESTIMATE for everyone.
        Instant saturday = at(LocalDate.of(2026, 11, 14), 36000);
        Evaluation evaluation = engine(true).evaluate(MEMBERS, AppliedConstraints.none(), saturday, Instant.EPOCH);
        assertThat(evaluation.source()).isEqualTo(RecommendationEngine.ESTIMATE);
        assertThat(evaluation.fallbackMembers()).isEqualTo(2);
        assertThat(evaluation.candidates()).containsOnlyKeys(2L, 3L, 4L);
        assertThat(evaluation.candidates().values()).flatMap(Candidate::trips)
                .allSatisfy(trip -> assertThat(trip.timed()).isNull());
    }

    @Test
    void withoutTheTimetableTheEngineIsTheFixedCostOne() {
        Instant appointment = at(THURSDAY, 32390);
        Evaluation withoutTimetable = engine(false).evaluate(MEMBERS, AppliedConstraints.none(), appointment, Instant.EPOCH);
        Evaluation plain = new RecommendationEngine(new DijkstraSubwayRouteFinder(SubwayGraph.of(TimetableRouterTest.DATA),
                new SubwayGraphProperties()), FARE, new RecommendationProperties()).evaluate(MEMBERS, AppliedConstraints.none());
        assertThat(withoutTimetable.source()).isEqualTo(RecommendationEngine.ESTIMATE);
        assertThat(withoutTimetable.fallbackMembers()).isEqualTo(2);
        assertThat(withoutTimetable.candidates().keySet()).isEqualTo(plain.candidates().keySet());
        withoutTimetable.candidates().forEach((station, candidate) -> assertThat(candidate.trips()).extracting(Trip::minutes)
                .isEqualTo(plain.candidates().get(station).trips().stream().map(Trip::minutes).toList()));
    }
}
