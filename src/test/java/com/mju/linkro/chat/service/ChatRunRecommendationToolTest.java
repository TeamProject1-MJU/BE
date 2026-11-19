package com.mju.linkro.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;

/** run_recommendation (#168) over mocked services: input checks, one run per question, outcomes and the run id kept for meta. */
class ChatRunRecommendationToolTest extends ChatToolsTestBase {
    static final String RUN_ID = "0192f3c1-0000-7000-8000-0000000000aa";

    RecommendationResponse response(AppliedConstraints applied, List<Result> results, String message) {
        return new RecommendationResponse(RUN_ID, "2026-11-17T10:00:00+09:00", false, List.of(), null, applied, message, results);
    }

    Result result(String criterion, long stationId, String name) {
        return new Result("res-" + criterion, criterion, new RecommendedStation(stationId, name, 37.5, 127.0, true),
                new Metrics(31.5, 4, 35, 1650, 1), List.of(), List.of(), "설명", "AI");
    }

    void created(RecommendationResponse response) {
        when(recommendations.create(eq(asker), eq(roomId), any(), any())).thenReturn(response);
    }

    RecommendationRequest sent() {
        var captor = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(recommendations).create(eq(asker), eq(roomId), captor.capture(), any());
        return captor.getValue();
    }

    String sentSummary() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(recommendations).create(eq(asker), eq(roomId), any(), captor.capture());
        return captor.getValue();
    }

    UUID runId() {
        return ((ChatTools.Asker) toolbox).recommendationRunId();
    }

    void assertFails(JsonNode result, String code) {
        assertThat(result.get("ok").asBoolean()).as(result.toString()).isFalse();
        assertThat(result.get("code").asString()).as(result.toString()).isEqualTo(code);
    }

    @Test
    void withoutArgumentsItRunsTheSame81RunWithNoConditionAndKeepsTheRunId() {
        created(response(AppliedConstraints.none(), List.of(result("FARE", 2, "강남"), result("TIME", 3, "종로3가")), null));

        JsonNode out = run("run_recommendation", "{}");

        assertThat(out.get("ok").asBoolean()).isTrue();
        assertThat(out.get("hasResults").asBoolean()).isTrue();
        assertThat(out.get("runId").asString()).isEqualTo(RUN_ID);
        assertThat(out.get("bestStation").get("station").asString()).isEqualTo("종로3가");
        assertThat(out.get("bestStation").get("maxMinutes").asInt()).isEqualTo(35);
        assertThat(out.get("bestStation").get("averageFare").asInt()).isEqualTo(1650);
        assertThat(sent().conditionText()).isNull();
        assertThat(sent().filter()).isNull();
        assertThat(runId()).isEqualTo(UUID.fromString(RUN_ID));
    }

    @Test
    void theFilterIsBuiltFromTheArgumentsWithStationNamesAndNicknamesResolvedOnTheServer() {
        created(response(new AppliedConstraints(40, 1, 2000, List.of(2L), List.of(new MemberLimit(minsu, 30))),
                List.of(result("TIME", 3, "종로3가")), null));

        JsonNode out = run("run_recommendation", "{\"maxMinutes\":40,\"maxTransfers\":1,\"maxFareWon\":2000,"
                + "\"excludeStations\":[\"강남역\"],\"memberLimits\":[{\"nickname\":\"민수\",\"maxMinutes\":30}]}");

        RecommendationRequest.Filter filter = sent().filter();
        assertThat(filter.maxMinutes()).isEqualTo(40);
        assertThat(filter.maxTransfers()).isEqualTo(1);
        assertThat(filter.maxFareWon()).isEqualTo(2000);
        assertThat(filter.excludeStationIds()).containsExactly(2L);
        assertThat(filter.memberLimits()).singleElement().satisfies(limit -> {
            assertThat(limit.userId()).isEqualTo(minsu);
            assertThat(limit.maxMinutes()).isEqualTo(30);
        });
        JsonNode applied = out.get("appliedConstraints");
        assertThat(applied.get("maxMinutes").asInt()).isEqualTo(40);
        assertThat(applied.get("excludedStations").get(0).asString()).isEqualTo("강남");
        assertThat(applied.get("memberLimits").get(0).get("nickname").asString()).isEqualTo("민수");
    }

    @Test
    void theConditionSummaryIsPassedAsDisplayTextOnlyNeverAsTheRequestsConditionText() {
        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));

        run("run_recommendation", "{\"conditionText\":\"  환승 1번 이하, 40분 안쪽  \",\"maxMinutes\":40,\"maxTransfers\":1}");

        assertThat(sentSummary()).isEqualTo("환승 1번 이하, 40분 안쪽");
        // the request the condition AI would read has no conditionText: the constraints come from the structured inputs
        assertThat(sent().conditionText()).isNull();
        assertThat(sent().filter().maxMinutes()).isEqualTo(40);
    }

    @Test
    void aSummaryWithoutStructuredArgumentsRunsUnfilteredAndTheResultShowsNoAppliedConstraints() {
        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));

        JsonNode out = run("run_recommendation", "{\"conditionText\":\"환승 1번 이하, 40분 안쪽\"}");

        assertThat(sent().filter()).isNull();
        assertThat(out.get("ok").asBoolean()).isTrue();
        // the model gets an explicit empty appliedConstraints, not a missing field, so it can tell that nothing was applied
        JsonNode applied = out.get("appliedConstraints");
        assertThat(applied.isObject()).isTrue();
        assertThat(applied.size()).isZero();
    }

    @Test
    void theAppliedConstraintsOfTheResultAreOnlyTheOnesTheRunReportsNotTheSummary() {
        created(response(new AppliedConstraints(40, null, null, List.of(), List.of()), List.of(result("TIME", 3, "종로3가")), null));

        JsonNode out = run("run_recommendation", "{\"conditionText\":\"환승 1번 이하, 40분 안쪽\",\"maxMinutes\":40}");

        JsonNode applied = out.get("appliedConstraints");
        assertThat(applied.get("maxMinutes").asInt()).isEqualTo(40);
        assertThat(applied.has("maxTransfers")).isFalse();
        assertThat(out.toString()).doesNotContain("환승 1번 이하");
    }

    @Test
    void thePromptTellsTheModelToStateOnlyAppliedConstraintsAndToSayWhenNoneWereApplied() throws Exception {
        String prompt;
        try (var in = getClass().getClassLoader().getResourceAsStream("ai/prompt/chat.txt")) {
            prompt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(prompt).contains("appliedConstraints에 있는 것만", "적용됐다고 말하지 않는다", "조건 없이 추천했다고 알리고");
    }

    @Test
    void aBlankOrMissingSummaryIsNull() {
        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));
        run("run_recommendation", "{\"conditionText\":\"   \"}");
        assertThat(sentSummary()).isNull();
        assertThat(((ChatTools.Asker) toolbox).recommendationRunId()).isNotNull();

        var other = tools.forAsker(roomId, asker);
        JSON.readTree(other.run("run_recommendation", "{\"conditionText\":null}"));
        JSON.readTree(tools.forAsker(roomId, asker).run("run_recommendation", "{}"));
        var captor = ArgumentCaptor.forClass(String.class);
        verify(recommendations, times(3)).create(eq(asker), eq(roomId), any(), captor.capture());
        assertThat(captor.getAllValues()).containsOnlyNulls();
    }

    @Test
    void aTooLongOrNonStringSummaryIsRefusedBeforeAnyRunAndKeepsTheQuestionsRun() {
        assertFails(run("run_recommendation", "{\"conditionText\":\"" + "가".repeat(501) + "\"}"), "VALIDATION_ERROR");
        assertFails(run("run_recommendation", "{\"conditionText\":40}"), "VALIDATION_ERROR");
        verify(recommendations, never()).create(any(), any(), any(), any());

        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));
        // exactly 500 characters is fine
        assertThat(run("run_recommendation", "{\"conditionText\":\"" + "가".repeat(500) + "\"}").get("ok").asBoolean()).isTrue();
    }

    @Test
    void invalidInputIsRefusedBeforeAnyRunAndDoesNotUseUpTheQuestionsOneRun() {
        for (String bad : List.of("{\"maxMinutes\":0}", "{\"maxMinutes\":301}", "{\"maxMinutes\":\"40\"}", "{\"maxMinutes\":40.5}",
                "{\"maxTransfers\":11}", "{\"maxTransfers\":-1}", "{\"maxFareWon\":0}", "{\"maxFareWon\":100001}",
                "{\"excludeStations\":\"강남\"}", "{\"excludeStations\":[3]}", "{\"memberLimits\":{}}",
                "{\"memberLimits\":[{\"nickname\":\"민수\"}]}", "{\"memberLimits\":[{\"nickname\":\"민수\",\"maxMinutes\":0}]}")) {
            assertFails(run("run_recommendation", bad), "VALIDATION_ERROR");
        }
        assertFails(run("run_recommendation", "{\"memberLimits\":[{\"nickname\":\"없는사람\",\"maxMinutes\":30}]}"), "MEMBER_NOT_FOUND");
        assertFails(run("run_recommendation", "{\"excludeStations\":[\"없는역\"]}"), "STATION_NOT_FOUND");
        assertFails(run("run_recommendation", "{\"excludeStations\":[\"서울\"]}"), "STATION_AMBIGUOUS");
        verify(recommendations, never()).create(any(), any(), any(), any());
        assertThat(runId()).isNull();

        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));
        assertThat(run("run_recommendation", "{\"maxMinutes\":40}").get("ok").asBoolean()).isTrue();
    }

    @Test
    void atMostOneRunPerQuestionEvenWhenTheFirstWasRefused() {
        created(response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null));
        assertThat(run("run_recommendation", "{}").get("ok").asBoolean()).isTrue();
        assertFails(run("run_recommendation", "{\"maxMinutes\":30}"), "ALREADY_RUN");
        verify(recommendations, times(1)).create(any(), any(), any(), any());
        assertThat(runId()).isEqualTo(UUID.fromString(RUN_ID));

        // another question has its own toolbox
        var other = tools.forAsker(roomId, asker);
        assertThat(JSON.readTree(other.run("run_recommendation", "{}")).get("ok").asBoolean()).isTrue();
        verify(recommendations, times(2)).create(any(), any(), any(), any());
    }

    @Test
    void aRefusedRunUsesUpTheQuestionsRunAndLeavesNoRunId() {
        when(recommendations.create(eq(asker), eq(roomId), any(), any())).thenThrow(new BusinessException(ErrorCode.TOO_FEW_MEMBERS));
        assertFails(run("run_recommendation", "{}"), "TOO_FEW_MEMBERS");
        assertFails(run("run_recommendation", "{}"), "ALREADY_RUN");
        verify(recommendations, times(1)).create(any(), any(), any(), any());
        assertThat(runId()).isNull();
    }

    @Test
    void the81ErrorsComeBackAsReasonsAndNoRunId() {
        record Case(BusinessException error, String code) {}
        for (Case failing : List.of(new Case(new BusinessException(ErrorCode.TOO_FEW_MEMBERS), "TOO_FEW_MEMBERS"),
                new Case(new BusinessException(ErrorCode.APPOINTMENT_PASSED), "APPOINTMENT_PASSED"),
                new Case(new BusinessException(ErrorCode.NO_SERVICE_TIME), "NO_SERVICE"),
                new Case(new BusinessException(ErrorCode.RECOMMENDATION_IN_PROGRESS), "RECOMMENDATION_IN_PROGRESS"),
                new Case(new BusinessException(ErrorCode.INTERNAL_ERROR), "NO_DATA"))) {
            var fresh = tools.forAsker(roomId, asker);
            when(recommendations.create(eq(asker), eq(roomId), any(), any())).thenThrow(failing.error());
            assertFails(JSON.readTree(fresh.run("run_recommendation", "{}")), failing.code());
            assertThat(((ChatTools.Asker) fresh).recommendationRunId()).isNull();
        }
    }

    @Test
    void aMemberWithoutADepartureStationIsNamedInTheReason() {
        when(recommendations.create(eq(asker), eq(roomId), any(), any())).thenThrow(new BusinessException(ErrorCode.DEPARTURE_MISSING));
        JsonNode out = run("run_recommendation", "{}");
        assertFails(out, "DEPARTURE_MISSING");
        assertThat(out.get("message").asString()).contains("지아").doesNotContain("민수");
        assertThat(runId()).isNull();
    }

    @Test
    void noResultsIsAnAnswerWithoutARunId() {
        created(response(new AppliedConstraints(5, null, null, List.of(), List.of()), List.of(), "조건에 맞는 역이 없어요."));

        JsonNode out = run("run_recommendation", "{\"maxMinutes\":5}");

        assertThat(out.get("ok").asBoolean()).isTrue();
        assertThat(out.get("hasResults").asBoolean()).isFalse();
        assertThat(out.has("runId")).isFalse();
        assertThat(out.has("bestStation")).isFalse();
        assertThat(out.get("appliedConstraints").get("maxMinutes").asInt()).isEqualTo(5);
        assertThat(runId()).isNull();
    }

    @Test
    void aRunThatOutlastsTheToolTimeoutIsNoDataAndKeepsNoRunId() {
        properties.setAiRecommendationToolTimeout(Duration.ofMillis(100));
        when(recommendations.create(eq(asker), eq(roomId), any(), any())).thenAnswer(call -> {
            Thread.sleep(1000);
            return response(AppliedConstraints.none(), List.of(result("TIME", 3, "종로3가")), null);
        });
        long started = System.nanoTime();

        assertFails(run("run_recommendation", "{}"), "NO_DATA");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
        assertThat(runId()).isNull();
    }

    @Test
    void theToolsOwnTimeoutDoesNotChangeTheReadOnlyToolsLimit() {
        properties.setAiRecommendationToolTimeout(Duration.ofSeconds(30));
        properties.setAiToolTimeout(Duration.ofMillis(100));
        when(recommendations.latest(asker, roomId)).thenAnswer(call -> {
            Thread.sleep(1000);
            return null;
        });
        assertFails(run("get_latest_recommendation", "{}"), "NO_DATA");
    }

    @Test
    void aNonMemberRunsNothing() {
        when(access.requireMember(roomId, asker)).thenThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER));
        assertFails(run("run_recommendation", "{}"), "NO_DATA");
        verify(recommendations, never()).create(any(), any(), any(), any());
        assertThat(runId()).isNull();
    }
}
