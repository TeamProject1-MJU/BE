package com.mju.linkro.recommendation.service;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.dto.RecommendationRequest.MemberLimit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationRequestTest {
    static final UUID MEMBER = UUID.randomUUID();

    @Test
    void emptyAndFullRequestsAreAccepted() {
        assertThatCode(() -> RecommendationRequest.orEmpty(null).validate(Set.of(MEMBER))).doesNotThrowAnyException();
        assertThatCode(() -> new RecommendationRequest("40분 이내", new Filter(40, 1, 2000, List.of(201L),
                List.of(new MemberLimit(MEMBER, 30)))).validate(Set.of(MEMBER))).doesNotThrowAnyException();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        var request = new RecommendationRequest("가".repeat(501), new Filter(0, 11, 100_001, List.of(0L),
                List.of(new MemberLimit(UUID.randomUUID(), 30), new MemberLimit(MEMBER, 301))));
        assertThatThrownBy(() -> request.validate(Set.of(MEMBER))).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(exception.getDetails()).containsOnlyKeys("conditionText", "filter.maxMinutes", "filter.maxTransfers",
                    "filter.maxFareWon", "filter.excludeStationIds", "filter.memberLimits[0].userId", "filter.memberLimits[1].maxMinutes");
        });
    }

    @Test
    void blankConditionTextCountsAsAbsent() {
        assertThat(new RecommendationRequest("   ", null).text()).isNull();
        assertThat(new RecommendationRequest(" 40분 이내 ", null).text()).isEqualTo("40분 이내");
    }
}
