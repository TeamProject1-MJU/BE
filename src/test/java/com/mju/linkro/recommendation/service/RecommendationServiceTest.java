package com.mju.linkro.recommendation.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ExplanationResult;
import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.RecommendationProperties;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Result;
import com.mju.linkro.recommendation.event.RecommendationCreatedEvent;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.subway.api.CongestionResult;
import com.mju.linkro.subway.api.CongestionSource;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The service with real engine, resolver, explanation writer and JSON; only the database and the AI are stand-ins. */
class RecommendationServiceTest {
    static final UUID USER_A = UUID.randomUUID();
    static final UUID USER_B = UUID.randomUUID();
    static final Instant APPOINTMENT = Instant.parse("2026-11-12T10:00:00Z");   // 19:00 in Seoul

    /** In-memory stand-in for the JDBC repository. */
    static class FakeRepository extends RecommendationRepository {
        final List<RunRow> runs = new ArrayList<>();
        final List<ResultRow> results = new ArrayList<>();
        final Map<Long, List<NeighbourRow>> neighbours = new HashMap<>();
        int saves = 0;

        FakeRepository() {
            super(null);
        }

        @Override public void save(RunRow run, List<ResultRow> rows) {
            saves++;
            runs.add(run);
            results.addAll(rows);
        }
        @Override public Optional<RunRow> latest(UUID roomId) {
            return runs.stream().filter(run -> run.roomId().equals(roomId))
                    .max(Comparator.comparing(RunRow::createdAt).thenComparing(RunRow::id));
        }
        @Override public Optional<RunRow> find(UUID runId) {
            return runs.stream().filter(run -> run.id().equals(runId)).findFirst();
        }
        @Override public List<ResultRow> results(UUID runId) {
            return results.stream().filter(row -> row.runId().equals(runId)).toList();
        }
        @Override public Map<String, List<Long>> supportedStationIdsByName() {
            return Map.of("역4", List.of(4L));
        }
        @Override public List<NeighbourRow> neighbours(long stationId) {
            return neighbours.getOrDefault(stationId, List.of());
        }
    }

    /** A clock the test moves forward between runs. */
    static class MovingClock extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    UUID roomId;
    MeetingRoom room;
    List<MemberRow> members;
    FakeRepository repository;
    Routes routes;
    RoomAccess access;
    RoomReadRepository roomReads;
    AiClient ai;
    ApplicationEventPublisher events;
    MovingClock clock;
    RecommendationInFlightGuard guard;
    RecommendationService service;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        room = new MeetingRoom("ABC123", "약속", USER_A);
        room.schedule(APPOINTMENT);
        members = new ArrayList<>(List.of(member(USER_A, "선호", 1L), member(USER_B, "인준", 2L)));
        repository = new FakeRepository();
        routes = RecommendationEngineTest.graph();
        access = mock(RoomAccess.class);
        when(access.requireMember(any(), any())).thenReturn(room);
        roomReads = mock(RoomReadRepository.class);
        when(roomReads.members(List.of(roomId))).thenAnswer(call -> List.copyOf(members));
        ai = spy(new MockAiClient());
        events = mock(ApplicationEventPublisher.class);
        clock = new MovingClock();
        guard = new RecommendationInFlightGuard(java.time.Duration.ofSeconds(60), clock);
        service = build(ai);
    }

    RecommendationService build(AiClient client) {
        StationLookup stations = new StationLookup() {
            @Override public StationInfo require(long id) { return new StationInfo(id, "역" + id, 37.0 + id / 100.0, 127.0, true); }
            @Override public StationInfo requireSupported(long id) { return require(id); }
        };
        var engine = new RecommendationEngine(routes.finder(), RecommendationEngineTest.fare(600, 1500), new RecommendationProperties());
        return new RecommendationService(access, roomReads, repository, new ConstraintResolver(client, repository), engine,
                new ExplanationWriter(client), stations,
                (path, departAt) -> path.totalSeconds() == 0 ? CongestionResult.unsupported()
                        : CongestionResult.of(92.5, "NORMAL", "보통", CongestionSource.MOCK),
                events, guard, JsonMapper.builder().build(), clock);
    }

    static MemberRow member(UUID user, String nickname, Long station) {
        return new MemberRow(UUID.randomUUID(), user, nickname, RoomRole.MEMBER, Instant.EPOCH, station);
    }

    RecommendationResponse create(RecommendationRequest request) {
        return service.create(USER_A, roomId, request);
    }

    // ---------------------------------------------------------------- pre-checks

    @Test
    void fewerThanTwoMembersIsRejectedBeforeAnyCalculation() {
        members.remove(1);
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_FEW_MEMBERS));
        assertThat(repository.saves).isZero();
        assertThat(routes.shortestPathsFromCalls).isZero();
        verify(events, times(0)).publishEvent(any(Object.class));
    }

    @Test
    void aSingleMemberWithoutDepartureIsTooFewMembers() {
        members.clear();
        members.add(member(USER_A, "선호", null));
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_FEW_MEMBERS));
    }

    @Test
    void aMemberWithoutDepartureRejectsTheWholeRequestAndListsEveryone() {
        UUID third = UUID.randomUUID();
        members.set(1, member(USER_B, "인준", null));
        members.add(member(third, "창범", null));
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DEPARTURE_MISSING);
            assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(422);
            assertThat((Object) exception.getDetails().get("missingUserIds")).isEqualTo(List.of(USER_B.toString(), third.toString()));
        });
        // no run, no calculation: the member is not simply left out
        assertThat(repository.saves).isZero();
        assertThat(repository.runs).isEmpty();
        assertThat(routes.shortestPathsFromCalls).isZero();
    }

    @Test
    void anInvalidFilterIsRejectedBeforeAnythingIsSaved() {
        var request = new RecommendationRequest(null, new Filter(null, null, null, null,
                List.of(new RecommendationRequest.MemberLimit(UUID.randomUUID(), 30))));
        assertThatThrownBy(() -> create(request)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThat(repository.saves).isZero();
    }

    // ---------------------------------------------------------------- results

    @Test
    void tripsAreStoredWithTheDepartureStationIdOnlyAndTheNameIsFilledOnRead() {
        RecommendationResponse created = create(null);

        String stored = repository.results.get(0).tripsJson();
        assertThat(stored).contains("\"departureStationId\":1").contains("\"departureStationId\":2");
        assertThat(stored).doesNotContain("\"departureStation\"");

        // GET returns the very same shape as POST, with the names filled in from the ids
        RecommendationResponse read = service.latest(USER_A, roomId);
        assertThat(read.results().get(0).participantTimes()).isEqualTo(created.results().get(0).participantTimes());
        var b = read.results().get(0).participantTimes().get(1);
        assertThat(b.departureStation().id()).isEqualTo(2L);
        assertThat(b.departureStation().name()).isEqualTo("역2");
    }

    @Test
    void rowsStoredBeforeWithAFullDepartureStationObjectAreStillRead() {
        create(null);
        var row = repository.results.get(0);
        String legacy = row.tripsJson().replace("\"departureStationId\":1", "\"departureStation\":{\"id\":1,\"name\":\"옛이름\"}")
                .replace("\"departureStationId\":2", "\"departureStation\":{\"id\":2,\"name\":\"옛이름\"}");
        repository.results.set(0, new RecommendationRepository.ResultRow(row.id(), row.runId(), row.criterion(), row.stationId(),
                row.metricValue(), legacy, row.nearbyJson(), row.reason(), row.reasonSource()));

        var times = service.latest(USER_A, roomId).results().get(0).participantTimes();
        assertThat(times).extracting(t -> t.departureStation().id()).containsExactly(1L, 2L);
        assertThat(times).extracting(t -> t.departureStation().name()).containsExactly("역1", "역2");   // current names, not the stored ones
    }

    @Test
    void rowsStoredBeforeWithADecimalCongestionPctAreStillRead() {
        create(null);
        var row = repository.results.get(0);
        assertThat(row.tripsJson()).contains("\"pct\":92");
        String legacy = row.tripsJson().replace("\"pct\":92", "\"pct\":93.0");   // how rows were stored while pct was a double
        assertThat(legacy).contains("\"pct\":93.0");
        repository.results.set(0, new RecommendationRepository.ResultRow(row.id(), row.runId(), row.criterion(), row.stationId(),
                row.metricValue(), legacy, row.nearbyJson(), row.reason(), row.reasonSource()));

        var times = service.latest(USER_A, roomId).results().get(0).participantTimes();
        assertThat(times.get(1).congestion().pct()).isEqualTo(93);
    }

    @Test
    void createsOneResultPerCriterionAndStoresThem() {
        repository.neighbours.put(3L, List.of(
                new RecommendationRepository.NeighbourRow(1, "역1", 2, 6, "MAIN", 4, "MAIN"),   // A's departure, must be dropped
                new RecommendationRepository.NeighbourRow(2, "역2", 2, 6, "MAIN", 5, "MAIN"),   // B's departure, must be dropped
                new RecommendationRepository.NeighbourRow(4, "역4", 2, 6, "MAIN", 8, "MAIN"),
                new RecommendationRepository.NeighbourRow(99, "역99", 2, 6, "MAIN", 3, "MAIN")));   // 99 is not a candidate

        RecommendationResponse response = create(null);

        assertThat(response.results()).extracting(Result::criterion).containsExactly("TIME", "FARE", "DISTANCE");
        assertThat(response.results()).extracting(result -> result.recommendedStation().id()).containsExactly(3L, 3L, 3L);
        assertThat(response.results()).extracting(Result::explanationSource).containsOnly("AI");
        assertThat(response.isStale()).isFalse();
        assertThat(response.staleReasons()).isEmpty();
        assertThat(response.message()).isNull();
        assertThat(response.conditionText()).isNull();
        assertThat(response.appliedConstraints().maxMinutes()).isNull();
        assertThat(response.createdAt()).isEqualTo("2026-11-12T09:00:00+09:00");

        // stored: one run, one row per criterion (unique), metric_value in minutes / won / metres
        assertThat(repository.runs).hasSize(1);
        assertThat(repository.results).extracting(RecommendationRepository.ResultRow::criterion).containsExactly("TIME", "FARE", "DISTANCE");
        assertThat(repository.results).extracting(row -> row.metricValue().intValue()).containsExactly(20, 1200, 7000);
        assertThat(repository.results).extracting(RecommendationRepository.ResultRow::runId).containsOnly(repository.runs.get(0).id());
        assertThat(repository.results).extracting(RecommendationRepository.ResultRow::reasonSource).containsOnly("AI");

        // TIME result in detail
        Result time = response.results().get(0);
        assertThat(time.recommendedStation().name()).isEqualTo("역3");
        assertThat(time.metrics().maxMinutes()).isEqualTo(20);
        assertThat(time.metrics().averageMinutes()).isEqualTo(17.5);
        assertThat(time.metrics().maxDifferenceMinutes()).isEqualTo(5);
        assertThat(time.metrics().averageFare()).isEqualTo(600);
        assertThat(time.metrics().totalTransfers()).isEqualTo(2);
        assertThat(time.participantTimes()).hasSize(2);
        var a = time.participantTimes().get(0);
        var b = time.participantTimes().get(1);
        assertThat(a.userId()).isEqualTo(USER_A);
        assertThat(a.travelMinutes()).isEqualTo(15);
        assertThat(a.fareWon()).isEqualTo(600);
        assertThat(a.distanceM()).isEqualTo(3000);
        assertThat(a.segments()).hasSize(1);
        assertThat(a.congestion().pct()).isEqualTo(92);   // whole number like the route API (92.5 from the fake lookup is cut by intValue, as RouteSearchService does)
        assertThat(a.departureAt()).isEqualTo("2026-11-12T18:45:00+09:00");   // appointment − 15 minutes
        assertThat(b.nickname()).isEqualTo("인준");
        assertThat(b.departureStation().id()).isEqualTo(2L);
        assertThat(b.travelMinutes()).isEqualTo(20);
        assertThat(b.fareWon()).isEqualTo(600);
        assertThat(b.distanceM()).isEqualTo(4000);
        assertThat(b.departureAt()).isEqualTo("2026-11-12T18:40:00+09:00");    // appointment − 20 minutes
        assertThat(b.congestion().pct()).isEqualTo(92);
        assertThat(b.segments()).hasSize(1);
        assertThat(b.segments().get(0).lineName()).isEqualTo("2호선");
        assertThat(b.segments().get(0).stationCount()).isEqualTo(3);
        // nearby: only station 4 (1 and 2 are departures, 99 cannot be reached by everyone)
        assertThat(time.nearbyAlternatives()).hasSize(1);
        assertThat(time.nearbyAlternatives().get(0).station().id()).isEqualTo(4L);
        assertThat(time.nearbyAlternatives().get(0).relation()).isEqualTo("NEXT");
        assertThat(time.nearbyAlternatives().get(0).relationLabel()).isEqualTo("최적역에서 1정거장");
        assertThat(time.nearbyAlternatives().get(0).maxMinutes()).isEqualTo(25);
        assertThat(time.nearbyAlternatives().get(0).totalFareWon()).isEqualTo(3000);
        assertThat(response.appliedConstraints().excludeStationIds()).isEmpty();
        assertThat(response.results()).allSatisfy(result -> {
            assertThat(result.recommendedStation().id()).isNotIn(1L, 2L);
            assertThat(result.nearbyAlternatives()).extracting(n -> n.station().id()).doesNotContain(1L, 2L);
        });

        // the departure of someone without an appointment is the request time
        assertThat(routes.shortestPathsFromCalls).isEqualTo(2);
    }

    @Test
    void withoutAnAppointmentTheDepartureIsTheRequestTime() {
        room.schedule(null);
        var b = create(null).results().get(0).participantTimes().get(1);
        assertThat(b.departureAt()).isEqualTo("2026-11-12T09:00:00+09:00");
    }

    @Test
    void aRunIsReadBackExactlyAsItWasReturned() {
        RecommendationResponse created = create(null);
        assertThat(service.latest(USER_A, roomId)).isEqualTo(created);
        assertThat(service.byRun(USER_A, roomId, UUID.fromString(created.runId()))).isEqualTo(created);
    }

    @Test
    void conditionTextIsInterpretedAndEchoedWithTheAppliedConstraints() {
        RecommendationResponse response = create(new RecommendationRequest("22분 이내로 가자", null));
        assertThat(response.conditionText()).isEqualTo("22분 이내로 가자");
        assertThat(response.appliedConstraints().maxMinutes()).isEqualTo(22);
        // st4 (25 min) is out; st1/st2 are departures, so every criterion keeps st3.
        assertThat(response.results()).extracting(result -> result.recommendedStation().id()).containsExactly(3L, 3L, 3L);
        assertThat(service.latest(USER_A, roomId).appliedConstraints().maxMinutes()).isEqualTo(22);
    }

    @Test
    void departureExclusionIsNotWrittenIntoAppliedExcludeStationIds() {
        RecommendationResponse response = create(new RecommendationRequest(null, new Filter(null, null, null, List.of(4L), null)));
        assertThat(response.appliedConstraints().excludeStationIds()).containsExactly(4L);
        assertThat(response.results()).isNotEmpty().allSatisfy(result ->
                assertThat(result.recommendedStation().id()).isEqualTo(3L));
    }

    // ---------------------------------------------------------------- no candidates

    @Test
    void noCandidateWhenDepartureExclusionLeavesNothingUsesTheSameMessage() {
        // 10 minutes would have left the two departure stations under the old rule; with them excluded nothing remains.
        RecommendationResponse response = create(new RecommendationRequest(null, new Filter(10, null, null, null, null)));
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).isEqualTo("조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.");
        assertThat(response.appliedConstraints().maxMinutes()).isEqualTo(10);
        assertThat(response.appliedConstraints().excludeStationIds()).isEmpty();
        assertThat(repository.runs).hasSize(1);
        assertThat(repository.results).isEmpty();
    }

    @Test
    void noCandidateIsASuccessWithAnEmptyListAndKeepsTheRun() {
        RecommendationResponse response = create(new RecommendationRequest(null, new Filter(1, null, null, null, null)));
        assertThat(response.results()).isEmpty();
        assertThat(response.message()).isEqualTo("조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.");
        assertThat(response.isStale()).isFalse();
        assertThat(response.staleReasons()).isEmpty();
        assertThat(repository.runs).hasSize(1);
        assertThat(repository.results).isEmpty();
        verify(events).publishEvent(any(RecommendationCreatedEvent.class));
        // no AI explanation is requested without a result
        verify(ai, times(0)).explainRecommendation(any());
        // the message is not stored; GET builds it again
        assertThat(service.latest(USER_A, roomId).message()).isEqualTo("조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.");
    }

    // ---------------------------------------------------------------- AI fallbacks

    @Test
    void conditionInterpretationFailureUsesTheFilterOnly() {
        RecommendationResponse response = create(new RecommendationRequest("__AI_FAIL__ 12분 이내", new Filter(null, null, 5000, null, null)));
        assertThat(response.message()).isEqualTo("자연어 조건을 해석하지 못해 적용하지 못했습니다");
        assertThat(response.appliedConstraints().maxMinutes()).isNull();      // the text was not applied
        assertThat(response.appliedConstraints().maxFareWon()).isEqualTo(5000);   // the filter was
        assertThat(response.results()).hasSize(3);                             // and the recommendation still ran
        // GET rebuilds the same notice from criteria.conditionParseFailed
        assertThat(service.latest(USER_A, roomId).message()).isEqualTo("자연어 조건을 해석하지 못해 적용하지 못했습니다");
        assertThat(repository.runs.get(0).criteriaJson()).contains("\"conditionParseFailed\":true");
    }

    @Test
    void failedConditionInterpretationAndNoCandidateGiveBothNotices() {
        RecommendationResponse response = create(new RecommendationRequest("__AI_QUOTA__", new Filter(1, null, null, null, null)));
        assertThat(response.message()).isEqualTo("자연어 조건을 해석하지 못해 적용하지 못했습니다. 조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.");
    }

    @Test
    void droppedAiItemsAreMentionedInThePostMessage() {
        AiClient dropping = mock(AiClient.class);
        when(dropping.interpretCondition(any())).thenReturn(new com.mju.linkro.ai.api.ConditionInterpretation(null, null, null,
                List.of("없는역"), List.of()));
        when(dropping.explainRecommendation(any())).thenReturn(new ExplanationResult(Map.of("TIME", "좋은 역입니다.")));
        RecommendationResponse response = build(dropping).create(USER_A, roomId, new RecommendationRequest("없는역 빼고", null));
        assertThat(response.message()).isEqualTo("적용하지 못한 조건: 제외 역 '없는역'.");
        assertThat(response.results()).hasSize(3);
    }

    @Test
    void aRecommendationMakesExactlyOneExplanationCall() {
        create(null);
        var captor = org.mockito.ArgumentCaptor.forClass(com.mju.linkro.ai.api.ExplanationRequest.class);
        verify(ai, times(1)).explainRecommendation(captor.capture());
        assertThat(captor.getValue().items()).extracting(item -> item.criterion()).containsExactly("TIME", "FARE", "DISTANCE");
        assertThat(captor.getValue().items()).extracting(item -> item.stationName()).containsExactly("역3", "역3", "역3");
        // the condition is interpreted separately (only when there is a conditionText), also exactly once
        verify(ai, times(0)).interpretCondition(any());
        create(new RecommendationRequest("22분 이내", null));
        verify(ai, times(1)).interpretCondition(any());
        verify(ai, times(2)).explainRecommendation(any());
    }

    @Test
    void wholeExplanationCallFailureGivesTemplatesForEveryCriterion() {
        doThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE)).when(ai).explainRecommendation(any());
        RecommendationResponse response = create(null);
        assertThat(response.results()).extracting(Result::explanationSource).containsOnly("TEMPLATE");
        assertThat(response.results()).extracting(Result::explanation).containsExactly(
                "가장 오래 걸리는 사람이 20분으로 모두에게 가장 공평한 역입니다.",
                "참여자 총요금이 1,200원으로 가장 저렴한 역입니다.",
                "참여자 이동거리 합이 7.0km로 가장 짧은 역입니다.");
        verify(ai, times(1)).explainRecommendation(any());
        assertThat(repository.results).extracting(RecommendationRepository.ResultRow::reasonSource).containsOnly("TEMPLATE");
    }

    @Test
    void aMismatchingNumberReplacesOnlyThatCriterionsExplanation() {
        AiClient wrong = mock(AiClient.class);
        when(wrong.explainRecommendation(any())).thenReturn(new ExplanationResult(Map.of(
                "TIME", "가장 오래 걸리는 사람이 99분입니다.", "FARE", "총요금이 1,200원입니다.")));
        RecommendationResponse response = build(wrong).create(USER_A, roomId, null);
        assertThat(response.results()).extracting(Result::explanationSource).containsExactly("TEMPLATE", "AI", "TEMPLATE");
        assertThat(response.results().get(0).explanation()).isEqualTo("가장 오래 걸리는 사람이 20분으로 모두에게 가장 공평한 역입니다.");
        assertThat(response.results().get(1).explanation()).isEqualTo("총요금이 1,200원입니다.");
        assertThat(response.results().get(2).explanation()).startsWith("참여자 이동거리 합이");   // the AI left DISTANCE out
        verify(wrong, times(1)).explainRecommendation(any());
    }

    // ---------------------------------------------------------------- event

    @Test
    void publishesRecommendationCreatedAfterSaving() {
        RecommendationResponse response = create(null);
        verify(events).publishEvent(new RecommendationCreatedEvent(roomId, UUID.fromString(response.runId()), USER_A,
                repository.runs.get(0).createdAt()));
    }

    @Test
    void aFailingEventListenerDoesNotFailTheRecommendation() {
        doThrow(new IllegalStateException("listener")).when(events).publishEvent(any(Object.class));
        assertThat(create(null).results()).hasSize(3);
        assertThat(repository.runs).hasSize(1);
    }

    // ---------------------------------------------------------------- reading and staleness

    @Test
    void latestWithoutAnyRunIsNull() {
        assertThat(service.latest(USER_A, roomId)).isNull();
    }

    @Test
    void latestComputesStalenessAtReadTime() {
        create(null);
        assertThat(service.latest(USER_A, roomId).isStale()).isFalse();
        assertThat(service.latest(USER_A, roomId).staleReasons()).isEmpty();

        room.schedule(APPOINTMENT.plusSeconds(3600));
        assertThat(service.latest(USER_A, roomId).isStale()).isTrue();
        assertThat(service.latest(USER_A, roomId).staleReasons()).containsExactly("APPOINTMENT_CHANGED");

        members.set(1, member(USER_B, "인준", 3L));            // departure station changed
        assertThat(service.latest(USER_A, roomId).staleReasons()).containsExactly("APPOINTMENT_CHANGED", "MEMBERS_CHANGED");

        room.schedule(APPOINTMENT);
        assertThat(service.latest(USER_A, roomId).staleReasons()).containsExactly("MEMBERS_CHANGED");
        members.set(1, member(USER_B, "인준", 2L));
        assertThat(service.latest(USER_A, roomId).isStale()).isFalse();   // changing it back is not stale
    }

    @Test
    void aNewMemberAndALeavingMemberBothMeanMembersChanged() {
        create(null);
        members.add(member(UUID.randomUUID(), "창범", null));
        assertThat(service.latest(USER_A, roomId).staleReasons()).containsExactly("MEMBERS_CHANGED");
        members.remove(2);
        members.remove(1);
        assertThat(service.latest(USER_A, roomId).staleReasons()).containsExactly("MEMBERS_CHANGED");
    }

    @Test
    void anOlderRunHasNoStaleness() {
        RecommendationResponse first = create(null);
        clock.now = clock.now.plusSeconds(60);
        RecommendationResponse second = create(null);
        room.schedule(APPOINTMENT.plusSeconds(60));   // both runs are now out of date, but only the latest says so

        assertThat(service.latest(USER_A, roomId).runId()).isEqualTo(second.runId());
        RecommendationResponse older = service.byRun(USER_A, roomId, UUID.fromString(first.runId()));
        assertThat(older.isStale()).isNull();
        assertThat(older.staleReasons()).isNull();
        assertThat(older.results()).hasSize(3);
        RecommendationResponse newest = service.byRun(USER_A, roomId, UUID.fromString(second.runId()));
        assertThat(newest.isStale()).isTrue();
        assertThat(newest.staleReasons()).containsExactly("APPOINTMENT_CHANGED");
    }

    @Test
    void aRunOfAnotherRoomOrAnUnknownRunIsRunNotFound() {
        RecommendationResponse created = create(null);
        UUID otherRoom = UUID.randomUUID();
        for (UUID run : List.of(UUID.fromString(created.runId()), UUID.randomUUID())) {
            UUID inRoom = run.toString().equals(created.runId()) ? otherRoom : roomId;
            assertThatThrownBy(() -> service.byRun(USER_A, inRoom, run)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RUN_NOT_FOUND));
        }
    }

    @Test
    void roomAccessErrorsPassThrough() {
        when(access.requireMember(any(), any())).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_ROOM_MEMBER));
        assertThatThrownBy(() -> service.latest(USER_A, roomId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.byRun(USER_A, roomId, UUID.randomUUID())).isInstanceOf(BusinessException.class);
        assertThat(repository.saves).isZero();
    }

    // ---- in-flight guard (#98) ----

    @Test
    void aSecondRequestWhileTheFirstCalculatesIs409WithoutAiOrRun() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(ai).explainRecommendation(any());
        RecommendationRequest withText = new RecommendationRequest("40분 이내", null);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.create(USER_A, roomId, withText));
            assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.create(USER_B, roomId, withText)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_IN_PROGRESS));
            // Reads are not guarded.
            assertThat(service.latest(USER_B, roomId)).isNull();

            release.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).results()).isNotEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        // Only the first request reached the AI and the repository.
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).interpretCondition(any());
        org.mockito.Mockito.verify(ai, org.mockito.Mockito.times(1)).explainRecommendation(any());
        assertThat(repository.saves).isEqualTo(1);

        // Once it is done the room is free again.
        assertThat(create(null).results()).isNotEmpty();
        assertThat(repository.saves).isEqualTo(2);
    }

    @Test
    void theGuardIsReleasedWhenTheCalculationFails() {
        // The second requireMember (reading the room back after saving) fails: a runtime exception, then a business one.
        when(access.requireMember(any(), any())).thenReturn(room).thenThrow(new IllegalStateException("boom"))
                .thenReturn(room).thenThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND)).thenReturn(room);
        assertThatThrownBy(() -> create(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND));
        assertThat(create(null).results()).isNotEmpty();
    }

    @Test
    void failedChecksNeverHoldTheRoom() {
        members.remove(1);
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_FEW_MEMBERS));
        RecommendationInFlightGuard.Ticket ticket = guard.tryAcquire(roomId).orElseThrow();
        guard.release(ticket);
    }

    @Test
    void aHeldRoomRefusesButOtherRoomsCalculate() {
        RecommendationInFlightGuard.Ticket ticket = guard.tryAcquire(roomId).orElseThrow();
        assertThatThrownBy(() -> create(null)).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RECOMMENDATION_IN_PROGRESS));
        assertThat(repository.saves).isZero();
        UUID otherRoom = UUID.randomUUID();
        when(roomReads.members(List.of(otherRoom))).thenAnswer(call -> List.copyOf(members));
        assertThat(service.create(USER_A, otherRoom, null).results()).isNotEmpty();
        guard.release(ticket);
        assertThat(create(null).results()).isNotEmpty();
    }

    // ---- AI latency (#99) ----

    @Test
    void anExplanationTimeoutFallsBackToTemplatesAndStillAnswers() {
        // What OpenAiClient throws when the explanation read timeout passes.
        doThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE)).when(ai).explainRecommendation(any());
        RecommendationResponse response = create(null);
        assertThat(response.results()).isNotEmpty().allSatisfy(result -> {
            assertThat(result.explanationSource()).isEqualTo("TEMPLATE");
            assertThat(result.explanation()).isNotBlank();
        });
        assertThat(repository.saves).isEqualTo(1);
    }

    @Test
    void aBlankConditionNeverCallsTheConditionAi() {
        create(new RecommendationRequest("   ", null));
        create(new RecommendationRequest("", null));
        create(new RecommendationRequest(null, null));
        create(null);
        verify(ai, times(0)).interpretCondition(any());
        create(new RecommendationRequest(" 40분 이내 ", null));
        verify(ai, times(1)).interpretCondition(any());
    }

    @Test
    void timingIsLoggedPerStageWithoutTheCondition() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RecommendationService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            create(new RecommendationRequest("민감한조건 40분 이내", null));
        } finally {
            logger.detachAppender(appender);
        }
        List<String> lines = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("Recommendation timing:")).toList();
        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line).matches("Recommendation timing: parse=\\d+ms evaluate=\\d+ms explain=\\d+ms save=\\d+ms total=\\d+ms "
                    + "\\(members 2, results \\d+\\)");
            assertThat(line).doesNotContain("민감한조건");
        });
    }
}
