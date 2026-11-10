package com.mju.linkro.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Data of POST /rooms/{roomId}/recommendations and GET .../latest and .../{runId} (API 8.1~8.3). Null fields are
 * always written. The nested records are also what is stored in recommendation_results.trips / nearby.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(name = "RecommendationData")
public record RecommendationResponse(
        String runId,
        @Schema(description = "ISO 8601, Asia/Seoul 오프셋", example = "2026-10-02T18:00:00+09:00") String createdAt,
        @JsonProperty("isStale") @Schema(description = "조회할 때 계산. 최근 실행이 아니면 null(판정 안 함)", types = {"boolean", "null"}) Boolean isStale,
        @Schema(description = "APPOINTMENT_CHANGED / MEMBERS_CHANGED. isStale=false이면 [], 최근 실행이 아니면 null", types = {"array", "null"}) List<String> staleReasons,
        @Schema(types = {"string", "null"}) String conditionText,
        AppliedConstraints appliedConstraints,
        @Schema(description = "안내 문구. 없으면 null", types = {"string", "null"}) String message,
        List<Result> results) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationAppliedConstraints")
    public record AppliedConstraints(
            @Schema(types = {"integer", "null"}) Integer maxMinutes,
            @Schema(types = {"integer", "null"}) Integer maxTransfers,
            @Schema(types = {"integer", "null"}) Integer maxFareWon,
            List<Long> excludeStationIds,
            List<MemberLimit> memberLimits) {

        public AppliedConstraints {
            excludeStationIds = excludeStationIds == null ? List.of() : List.copyOf(excludeStationIds);
            memberLimits = memberLimits == null ? List.of() : List.copyOf(memberLimits);
        }

        public static AppliedConstraints none() {
            return new AppliedConstraints(null, null, null, List.of(), List.of());
        }
    }

    @Schema(name = "RecommendationAppliedMemberLimit")
    public record MemberLimit(UUID userId, int maxMinutes) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationResult")
    public record Result(
            String resultId,
            @Schema(description = "TIME / FARE / DISTANCE", example = "TIME") String criterion,
            RecommendedStation recommendedStation,
            Metrics metrics,
            List<ParticipantTime> participantTimes,
            List<NearbyAlternative> nearbyAlternatives,
            String explanation,
            @Schema(description = "AI / TEMPLATE", example = "AI") String explanationSource) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendedStation")
    public record RecommendedStation(long id, String name, double latitude, double longitude,
                                     @JsonProperty("isSupported") boolean isSupported) {}

    /** Computed from participantTimes, never stored. */
    @Schema(name = "RecommendationMetrics")
    public record Metrics(double averageMinutes, int maxDifferenceMinutes, int maxMinutes, int averageFare, int totalTransfers) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationParticipantTime")
    public record ParticipantTime(
            UUID userId,
            String nickname,
            StationRef departureStation,
            int travelMinutes,
            int transferCount,
            int fareWon,
            int distanceM,
            @Schema(description = "출발 예정 시각 = 약속 시각 − travelMinutes(약속 시각이 없으면 요청 시각)") String departureAt,
            @Schema(description = "경로 모든 정차역 혼잡도 중 최댓값. 데이터 없으면 null", types = {"object", "null"}) Congestion congestion,
            List<Segment> segments) {}

    @Schema(name = "RecommendationStationRef")
    public record StationRef(long id, String name) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(name = "RecommendationCongestion")
    public record Congestion(@Schema(description = "정수(경로 RouteCongestionData.pct와 같은 표시값)", example = "93") int pct, String level, String levelLabel) {}

    @Schema(name = "RecommendationSegment")
    public record Segment(long lineId, String lineName, String color, String direction,
                          String fromStationName, String toStationName, int stationCount) {}

    @Schema(name = "RecommendationNearbyAlternative")
    public record NearbyAlternative(StationRef station, long lineId,
                                    @Schema(description = "PREV / NEXT") String relation,
                                    @Schema(example = "최적역에서 1정거장") String relationLabel,
                                    int maxMinutes, int totalFareWon) {}
}
