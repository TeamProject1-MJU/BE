package com.mju.linkro.route.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** POST /routes/search body (6.1). Every invalid field is reported at once as VALIDATION_ERROR (400). */
@Schema(name = "RouteSearchRequest", requiredProperties = {"originStationId", "destinationStationId"})
public record RouteSearchRequest(
        @Schema(description = "출발역 ID (isSupported=true)", example = "31") Long originStationId,
        @Schema(description = "도착역 ID (isSupported=true)", example = "35") Long destinationStationId,
        @Schema(description = "선택. 출발 시각(ISO-8601, 오프셋 포함). 생략하면 요청 시각. 혼자 모드(열차 시각표)는 이 시각 이후 첫 열차로 경로를 찾고, 그 밖에는 혼잡도 기준 시각. 지난 시각도 그대로 쓴다. arriveAt과 함께 보낼 수 없다",
                example = "2026-11-06T08:00:00+09:00") String departAt,
        @Schema(description = "선택. 방 화면에서 호출할 때 방 ID. 방 멤버를 확인하고 약속 시각 기준으로 출발 예정 시각을 계산. arriveAt을 보내면 멤버 확인만 하고 약속 시각은 쓰지 않는다",
                example = "0192f3a4-8c2d-7d4f-8b21-6e3f9a7c2b02") String roomId,
        @Schema(description = "선택. 도착 시각(ISO-8601, 오프셋 포함). 이 시각까지 도착하는 가장 늦은 출발 경로를 열차 시각표로 찾는다(약속방 모드와 같은 계산). "
                + "departAt과 함께 보낼 수 없고, 지금 이후 설정값(기본 30일) 이내여야 한다. 열차 시각표가 없으면 무시된다",
                example = "2026-11-06T19:00:00+09:00") String arriveAt) {

    /** Without arriveAt, as before #130's arrival mode. */
    public RouteSearchRequest(Long originStationId, Long destinationStationId, String departAt, String roomId) {
        this(originStationId, destinationStationId, departAt, roomId, null);
    }

    public record Valid(long originStationId, long destinationStationId, Instant departAt, UUID roomId, Instant arriveAt) {}

    /**
     * @param now            the request time: arriveAt before it (whole seconds, so this second is allowed) is invalid
     * @param arriveAtMaxAhead how far after {@code now} arriveAt may be
     */
    public Valid validate(Instant now, Duration arriveAtMaxAhead) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (originStationId == null || originStationId <= 0) errors.put("originStationId", "originStationId는 1 이상의 정수여야 합니다.");
        if (destinationStationId == null || destinationStationId <= 0) errors.put("destinationStationId", "destinationStationId는 1 이상의 정수여야 합니다.");
        Instant depart = null;
        if (departAt != null) {
            try {
                depart = OffsetDateTime.parse(departAt.strip()).toInstant();
            } catch (DateTimeParseException exception) {
                errors.put("departAt", "departAt은 오프셋을 포함한 ISO-8601 시각이어야 합니다(예: 2026-11-06T08:00:00+09:00).");
            }
        }
        UUID room = null;
        if (roomId != null) {
            try {
                room = UUID.fromString(roomId.strip());
            } catch (IllegalArgumentException exception) {
                errors.put("roomId", "roomId는 UUID여야 합니다.");
            }
        }
        Instant arrive = null;
        if (arriveAt != null) {
            try {
                arrive = OffsetDateTime.parse(arriveAt.strip()).toInstant();
            } catch (DateTimeParseException exception) {
                errors.put("arriveAt", "arriveAt은 오프셋을 포함한 ISO-8601 시각이어야 합니다(예: 2026-11-06T19:00:00+09:00).");
            }
            if (departAt != null) {
                errors.put("departAt", "departAt과 arriveAt은 함께 보낼 수 없습니다. 출발 시각이나 도착 시각 중 하나만 보내 주세요.");
                errors.putIfAbsent("arriveAt", "departAt과 arriveAt은 함께 보낼 수 없습니다. 출발 시각이나 도착 시각 중 하나만 보내 주세요.");
            }
        }
        if (arrive != null && !errors.containsKey("arriveAt")) {
            if (arrive.truncatedTo(ChronoUnit.SECONDS).isBefore(now.truncatedTo(ChronoUnit.SECONDS))) {
                errors.put("arriveAt", "arriveAt은 지금 이후여야 합니다.");
            } else if (arrive.isAfter(now.plus(arriveAtMaxAhead))) {
                errors.put("arriveAt", "arriveAt은 지금부터 " + arriveAtMaxAhead.toDays() + "일 이내여야 합니다.");
            }
        }
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        return new Valid(originStationId, destinationStationId, depart, room, arrive);
    }
}
