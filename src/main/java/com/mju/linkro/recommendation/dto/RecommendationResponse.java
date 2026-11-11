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
            @Schema(description = "출발 예정 시각. TIMETABLE: 첫 열차 출발 시각 − 역 접근 시간(늦어도 이때 나서면 약속 시각에 도착). "
                    + "ESTIMATE: 약속 시각 − travelMinutes(약속 시각이 없으면 요청 시각)") String departureAt,
            @Schema(description = "경로 모든 정차역 혼잡도 중 최댓값. 데이터 없으면 null", types = {"object", "null"}) Congestion congestion,
            List<Segment> segments,
            @Schema(description = "처음 타는 열차. TIMETABLE일 때만 있고 ESTIMATE와 이전 실행은 null", types = {"object", "null"}) BoardingTrain boardingTrain,
            @Schema(description = "열차별 승차·하차 시각과 환승 대기. ESTIMATE는 []") List<Leg> legs,
            @Schema(description = "TIMETABLE(열차 시각표로 계산) / ESTIMATE(고정 소요시간 추정). 이전 실행은 null",
                    example = "TIMETABLE", types = {"string", "null"}) String source) {}

    @Schema(name = "RecommendationBoardingTrain")
    public record BoardingTrain(
            long lineId,
            String lineName,
            long stationId,
            String stationName,
            @Schema(description = "UP / DOWN / INNER / OUTER / LOOP (segments.direction과 같은 기준)") String direction,
            @Schema(description = "열차 출발 시각, ISO 8601 Asia/Seoul", example = "2026-11-13T18:21:30+09:00") String departureAt,
            @Schema(description = "열차 종착역(시각표 표기)", example = "성수") String terminal,
            @Schema(description = "시각표 열차 번호", example = "2183") String trainCode) {}

    @Schema(name = "RecommendationLeg")
    public record Leg(
            long lineId,
            String lineName,
            String trainCode,
            String terminal,
            String direction,
            String fromStationName,
            String toStationName,
            @Schema(description = "승차(열차 출발) 시각") String boardAt,
            @Schema(description = "하차(열차 도착) 시각") String alightAt,
            int stationCount,
            @Schema(description = "타기 전 환승 도보 초. 첫 열차와 같은 승강장 갈아타기는 0") int walkSec,
            @Schema(description = "타기 전 승강장 대기 초 = 승차 − (이전 하차 + 도보). 첫 열차는 0") int transferWaitSec) {}

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
