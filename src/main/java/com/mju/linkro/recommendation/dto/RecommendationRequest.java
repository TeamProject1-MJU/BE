package com.mju.linkro.recommendation.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * POST /rooms/{roomId}/recommendations body (API 8.1). Both fields are optional; the same item in {@code filter}
 * wins over what {@code conditionText} says.
 */
@Schema(name = "RecommendationRequest")
public record RecommendationRequest(
        @Schema(description = "선택. 자연어 조건. AI가 filter 항목으로 해석", example = "다들 40분 안쪽이고 환승은 한 번 이하였으면 좋겠어") String conditionText,
        @Schema(description = "선택. 구조화 조건. 같은 항목이 conditionText에도 있으면 filter 값이 우선") Filter filter) {

    /** The longest conditionText accepted. Not defined by the API spec. */
    public static final int MAX_CONDITION_TEXT_LENGTH = 500;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 300;
    public static final int MIN_TRANSFERS = 0;
    public static final int MAX_TRANSFERS = 10;
    public static final int MIN_FARE_WON = 1;
    public static final int MAX_FARE_WON = 100_000;

    @Schema(name = "RecommendationFilter")
    public record Filter(
            @Schema(description = "참여자 한 명당 최대 소요시간(분)", example = "40") Integer maxMinutes,
            @Schema(description = "참여자 한 명당 최대 환승 횟수", example = "1") Integer maxTransfers,
            @Schema(description = "참여자 한 명당 최대 요금(원)", example = "2000") Integer maxFareWon,
            @Schema(description = "제외할 역 ID", example = "[201]") List<Long> excludeStationIds,
            @Schema(description = "개인별 최대 소요시간") List<MemberLimit> memberLimits) {}

    @Schema(name = "RecommendationMemberLimit")
    public record MemberLimit(
            @Schema(example = "0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03") UUID userId,
            @Schema(example = "30") Integer maxMinutes) {}

    /** The request as an object that is never null, so callers need no null checks. */
    public static RecommendationRequest orEmpty(RecommendationRequest request) {
        return request == null ? new RecommendationRequest(null, null) : request;
    }

    /** conditionText without surrounding spaces, or null when blank. */
    public String text() {
        return conditionText == null || conditionText.isBlank() ? null : conditionText.strip();
    }

    /**
     * Range checks of everything the caller wrote by hand. All problems are reported at once as VALIDATION_ERROR
     * (400). A memberLimits userId must belong to the room.
     */
    public void validate(Set<UUID> memberIds) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (conditionText != null && conditionText.strip().length() > MAX_CONDITION_TEXT_LENGTH) {
            errors.put("conditionText", "conditionText는 " + MAX_CONDITION_TEXT_LENGTH + "자 이하여야 합니다.");
        }
        if (filter != null) {
            range(errors, "filter.maxMinutes", filter.maxMinutes(), MIN_MINUTES, MAX_MINUTES);
            range(errors, "filter.maxTransfers", filter.maxTransfers(), MIN_TRANSFERS, MAX_TRANSFERS);
            range(errors, "filter.maxFareWon", filter.maxFareWon(), MIN_FARE_WON, MAX_FARE_WON);
            if (filter.excludeStationIds() != null && filter.excludeStationIds().stream().anyMatch(id -> id == null || id <= 0)) {
                errors.put("filter.excludeStationIds", "excludeStationIds는 1 이상의 정수 배열이어야 합니다.");
            }
            if (filter.memberLimits() != null) {
                for (int i = 0; i < filter.memberLimits().size(); i++) {
                    MemberLimit limit = filter.memberLimits().get(i);
                    String key = "filter.memberLimits[" + i + "]";
                    if (limit == null || limit.userId() == null || !memberIds.contains(limit.userId())) {
                        errors.put(key + ".userId", "userId는 이 방 멤버의 ID여야 합니다.");
                    }
                    range(errors, key + ".maxMinutes", limit == null ? null : limit.maxMinutes(), MIN_MINUTES, MAX_MINUTES, true);
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        }
    }

    private static void range(Map<String, String> errors, String key, Integer value, int min, int max) {
        range(errors, key, value, min, max, false);
    }

    private static void range(Map<String, String> errors, String key, Integer value, int min, int max, boolean required) {
        if ((value == null && required) || (value != null && (value < min || value > max))) {
            errors.put(key, key.substring(key.lastIndexOf('.') + 1) + "는 " + min + " 이상 " + max + " 이하의 정수여야 합니다.");
        }
    }
}
