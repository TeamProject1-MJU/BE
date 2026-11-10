package com.mju.linkro.recommendation.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ConditionInterpretation;
import com.mju.linkro.ai.api.MemberLimit;
import com.mju.linkro.ai.mock.MockAiClient;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.service.Candidate.Member;
import com.mju.linkro.recommendation.service.ConstraintResolver.Resolved;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConstraintResolverTest {
    static final UUID MINSU = UUID.randomUUID();
    static final UUID JIYOUNG = UUID.randomUUID();
    static final List<Member> MEMBERS = List.of(new Member(MINSU, "민수", 1), new Member(JIYOUNG, "지영", 2));

    static RecommendationRepository stations() {
        RecommendationRepository repository = mock(RecommendationRepository.class);
        when(repository.supportedStationIdsByName()).thenReturn(Map.of("강남", List.of(201L), "홍대입구", List.of(202L), "신길", List.of(1L, 2L)));
        return repository;
    }

    static Resolved resolve(AiClient ai, String text, Filter filter) {
        return new ConstraintResolver(ai, stations()).resolve(new RecommendationRequest(text, filter), MEMBERS);
    }

    @Test
    void withoutAnyConditionNothingIsApplied() {
        AiClient ai = mock(AiClient.class);
        Resolved resolved = new ConstraintResolver(ai, stations()).resolve(new RecommendationRequest(null, null), MEMBERS);
        assertThat(resolved.applied().maxMinutes()).isNull();
        assertThat(resolved.applied().excludeStationIds()).isEmpty();
        assertThat(resolved.applied().memberLimits()).isEmpty();
        assertThat(resolved.parseFailed()).isFalse();
        verify(ai, never()).interpretCondition(any());
    }

    @Test
    void aiResultIsMappedToIds() {
        Resolved resolved = resolve(new MockAiClient(), "40분 이내, 2000원 이하, 환승 1번 이하, 강남 빼고, 지영은 30분", null);
        assertThat(resolved.applied().maxMinutes()).isNull();   // the mock leaves maxMinutes null when memberLimits are present
        assertThat(resolved.applied().maxTransfers()).isEqualTo(1);
        assertThat(resolved.applied().maxFareWon()).isEqualTo(2000);
        assertThat(resolved.applied().excludeStationIds()).containsExactly(201L);
        assertThat(resolved.applied().memberLimits()).extracting(limit -> limit.userId() + ":" + limit.maxMinutes())
                .containsExactly(JIYOUNG + ":30");
        assertThat(resolved.parseFailed()).isFalse();
        assertThat(resolved.dropped()).isEmpty();
    }

    @Test
    void filterItemsWinOverTheAiItems() {
        Filter filter = new Filter(null, 0, 1800, List.of(202L), List.of(new RecommendationRequest.MemberLimit(MINSU, 25)));
        Resolved resolved = resolve(new MockAiClient(), "40분 이내, 2000원 이하, 환승 1번 이하, 강남 빼고", filter);
        assertThat(resolved.applied().maxMinutes()).isEqualTo(40);        // only the AI said it
        assertThat(resolved.applied().maxTransfers()).isEqualTo(0);       // filter wins
        assertThat(resolved.applied().maxFareWon()).isEqualTo(1800);      // filter wins
        assertThat(resolved.applied().excludeStationIds()).containsExactly(202L);
        assertThat(resolved.applied().memberLimits()).extracting(limit -> limit.userId() + ":" + limit.maxMinutes())
                .containsExactly(MINSU + ":25");
        // the same item from both sides: the filter's memberLimits replace the AI's
        assertThat(resolve(new MockAiClient(), "지영은 30분", filter).applied().memberLimits())
                .extracting(limit -> limit.userId() + ":" + limit.maxMinutes()).containsExactly(MINSU + ":25");
    }

    @Test
    void whatCannotBeMappedOrIsOutOfRangeIsDropped() {
        AiClient ai = mock(AiClient.class);
        when(ai.interpretCondition(any())).thenReturn(new ConditionInterpretation(0, 99, 5, List.of("분당", "신길", "강남역"),
                List.of(new MemberLimit("철수", 30), new MemberLimit("민수", 0), new MemberLimit("지영", 20))));
        Resolved resolved = resolve(ai, "아무 말", null);
        // 0 minutes and 99 transfers are outside the ranges; 5 won is valid (it will simply leave no candidate).
        assertThat(resolved.applied().maxMinutes()).isNull();
        assertThat(resolved.applied().maxTransfers()).isNull();
        assertThat(resolved.applied().maxFareWon()).isEqualTo(5);
        // 분당 is unknown, 신길 belongs to two stations, 강남역 is 강남 once the trailing 역 is removed.
        assertThat(resolved.applied().excludeStationIds()).containsExactly(201L);
        assertThat(resolved.applied().memberLimits()).extracting(limit -> limit.userId() + ":" + limit.maxMinutes())
                .containsExactly(JIYOUNG + ":20");
        assertThat(resolved.dropped()).containsExactly("최대 시간", "최대 환승", "제외 역 '분당'", "제외 역 '신길'",
                "개인 한도 '철수'", "개인 한도 '민수'");
        assertThat(resolved.parseFailed()).isFalse();
    }

    @Test
    void anAiFailureUsesTheFilterOnlyAndSetsParseFailed() {
        Filter filter = new Filter(35, null, null, null, null);
        Resolved resolved = resolve(new MockAiClient(), "__AI_FAIL__ 40분 이내", filter);
        assertThat(resolved.parseFailed()).isTrue();
        assertThat(resolved.applied().maxMinutes()).isEqualTo(35);
        assertThat(resolved.applied().maxFareWon()).isNull();
        // an exhausted quota is handled the same way, and so is any unexpected error
        assertThat(resolve(new MockAiClient(), "__AI_QUOTA__", null).parseFailed()).isTrue();
        AiClient broken = mock(AiClient.class);
        when(broken.interpretCondition(any())).thenThrow(new IllegalStateException("boom"));
        assertThat(resolve(broken, "40분 이내", null).parseFailed()).isTrue();
    }

    @Test
    void theAiGetsNicknamesAndOnlyStationNamesTheTextMentions() {
        AiClient ai = mock(AiClient.class);
        when(ai.interpretCondition(any())).thenReturn(new ConditionInterpretation(null, null, null, List.of(), List.of()));
        resolve(ai, "강남 빼고 홍대입구는 좋아", null);
        var captor = org.mockito.ArgumentCaptor.forClass(com.mju.linkro.ai.api.ConditionRequest.class);
        verify(ai).interpretCondition(captor.capture());
        assertThat(captor.getValue().memberNicknames()).containsExactly("민수", "지영");
        assertThat(captor.getValue().stationNameHints()).containsExactly("강남", "홍대입구");
    }
}
