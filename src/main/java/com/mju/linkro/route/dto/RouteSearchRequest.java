package com.mju.linkro.route.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** POST /routes/search body (6.1). Every invalid field is reported at once as VALIDATION_ERROR (400). */
@Schema(name = "RouteSearchRequest", requiredProperties = {"originStationId", "destinationStationId"})
public record RouteSearchRequest(
        @Schema(description = "출발역 ID (isSupported=true)", example = "31") Long originStationId,
        @Schema(description = "도착역 ID (isSupported=true)", example = "35") Long destinationStationId,
        @Schema(description = "선택. 혼잡도 기준 시각(ISO-8601, 오프셋 포함). 생략하면 요청 시각", example = "2026-11-06T08:00:00+09:00") String departAt,
        @Schema(description = "선택. 방 화면에서 호출할 때 방 ID. 방 멤버를 확인하고 약속 시각 기준으로 출발 예정 시각을 계산", example = "0192f3a4-8c2d-7d4f-8b21-6e3f9a7c2b02") String roomId) {

    public record Valid(long originStationId, long destinationStationId, Instant departAt, UUID roomId) {}

    public Valid validate() {
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
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        return new Valid(originStationId, destinationStationId, depart, room);
    }
}
