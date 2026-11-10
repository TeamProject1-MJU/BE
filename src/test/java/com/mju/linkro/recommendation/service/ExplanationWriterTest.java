package com.mju.linkro.recommendation.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ExplanationRequest;
import com.mju.linkro.ai.api.ExplanationResult;
import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Metrics;
import com.mju.linkro.recommendation.service.ExplanationWriter.Explanation;
import com.mju.linkro.recommendation.service.ExplanationWriter.Facts;
import com.mju.linkro.recommendation.service.ExplanationWriter.Item;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplanationWriterTest {
    // 2 people, slowest 28 min, average 24.3, spread 9, total fare 3100 (average 1550), 18.8 km, 1 transfer
    static final Metrics METRICS = new Metrics(24.3, 9, 28, 1550, 1);

    static Facts facts(Criterion criterion) {
        return new Facts(criterion, 2, METRICS, 3100, 18_800);
    }

    static Item item(Criterion criterion) {
        return new Item(facts(criterion), "사당");
    }

    static final List<Item> ALL = List.of(item(Criterion.TIME), item(Criterion.FARE), item(Criterion.DISTANCE));

    private static AiClient saying(Map<String, String> texts) {
        AiClient ai = mock(AiClient.class);
        when(ai.explainRecommendation(any())).thenReturn(new ExplanationResult(texts));
        return ai;
    }

    @Test
    void oneCallExplainsEveryCriterion() {
        AiClient ai = saying(Map.of("TIME", "가장 오래 걸리는 사람도 28분이고 총요금은 3,100원입니다.",
                "FARE", "총요금이 3,100원입니다.", "DISTANCE", "이동거리 합은 18.8km입니다."));
        var explanations = new ExplanationWriter(ai).explain(ALL);
        assertThat(explanations).containsOnlyKeys(Criterion.TIME, Criterion.FARE, Criterion.DISTANCE);
        assertThat(explanations.get(Criterion.TIME)).isEqualTo(new Explanation("가장 오래 걸리는 사람도 28분이고 총요금은 3,100원입니다.", "AI"));
        assertThat(explanations.values()).extracting(Explanation::source).containsOnly("AI");
        verify(ai, times(1)).explainRecommendation(any());
    }

    @Test
    void theCallCarriesAllItemsWithOnlyServerComputedFacts() {
        AiClient ai = saying(Map.of());
        new ExplanationWriter(ai).explain(ALL);
        var captor = ArgumentCaptor.forClass(ExplanationRequest.class);
        verify(ai).explainRecommendation(captor.capture());
        assertThat(captor.getValue().items()).extracting(ExplanationRequest.Item::criterion).containsExactly("TIME", "FARE", "DISTANCE");
        assertThat(captor.getValue().items()).extracting(ExplanationRequest.Item::stationName).containsOnly("사당");
        assertThat(captor.getValue().items().get(0).facts()).containsEntry("maxMinutes", 28).containsEntry("totalFareWon", 3100)
                .containsEntry("totalDistanceKm", 18.8).containsEntry("participantCount", 2);
    }

    @Test
    void sentencesWithoutNumbersAreKept() {
        var explanations = new ExplanationWriter(new MockAiClient()).explain(ALL);
        assertThat(explanations.values()).extracting(Explanation::source).containsOnly("AI");
    }

    @Test
    void digitsInStationAndLineNamesAreNotClaims() {
        var explanations = new ExplanationWriter(saying(Map.of("TIME", "종로3가는 2호선과 5호선 모두 가깝고 가장 오래 걸리는 사람도 28분입니다.")))
                .explain(List.of(new Item(facts(Criterion.TIME), "종로3가")));
        assertThat(explanations.get(Criterion.TIME).source()).isEqualTo("AI");
    }

    @Test
    void aMismatchingNumberFallsBackToTheTemplateForThatCriterionOnly() {
        var explanations = new ExplanationWriter(saying(Map.of("TIME", "가장 오래 걸리는 사람이 27분입니다.",
                "FARE", "총요금이 3,100원입니다.", "DISTANCE", "이동거리 합은 18.8km입니다."))).explain(ALL);
        assertThat(explanations.get(Criterion.TIME))
                .isEqualTo(new Explanation("가장 오래 걸리는 사람이 28분으로 모두에게 가장 공평한 역입니다.", "TEMPLATE"));
        assertThat(explanations.get(Criterion.FARE).source()).isEqualTo("AI");
        assertThat(explanations.get(Criterion.DISTANCE).source()).isEqualTo("AI");
    }

    @Test
    void aMissingOrBlankSentenceFallsBackForThatCriterionOnly() {
        var explanations = new ExplanationWriter(saying(Map.of("TIME", "   ", "FARE", "총요금이 3,100원입니다."))).explain(ALL);
        assertThat(explanations.get(Criterion.TIME).source()).isEqualTo("TEMPLATE");
        assertThat(explanations.get(Criterion.FARE).source()).isEqualTo("AI");
        assertThat(explanations.get(Criterion.DISTANCE))
                .isEqualTo(new Explanation("참여자 이동거리 합이 18.8km로 가장 짧은 역입니다.", "TEMPLATE"));
    }

    @Test
    void aFailedCallGivesEveryCriterionItsTemplate() {
        AiClient ai = mock(AiClient.class);
        when(ai.explainRecommendation(any())).thenThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE));
        var explanations = new ExplanationWriter(ai).explain(ALL);
        assertThat(explanations.values()).extracting(Explanation::source).containsOnly("TEMPLATE");
        assertThat(explanations.get(Criterion.FARE).text()).isEqualTo("참여자 총요금이 3,100원으로 가장 저렴한 역입니다.");
        verify(ai, times(1)).explainRecommendation(any());
    }

    @Test
    void nothingToExplainMakesNoCall() {
        AiClient ai = mock(AiClient.class);
        assertThat(new ExplanationWriter(ai).explain(List.of())).isEmpty();
        verify(ai, never()).explainRecommendation(any());
    }
}
