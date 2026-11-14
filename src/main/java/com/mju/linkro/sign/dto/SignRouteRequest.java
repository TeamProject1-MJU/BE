package com.mju.linkro.sign.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code route} part of POST /ai/direction-check (9.1). Field format errors are reported as VALIDATION_ERROR (400). */
@Schema(name = "SignRouteRequest", requiredProperties = {"stationId", "lineId"},
        description = "stationId와 lineId 필수. headsign이 없거나 공백이면 direction 필수.")
public record SignRouteRequest(
        @Schema(description = "표지판이 있는 역 ID", example = "105") Long stationId,
        @Schema(description = "이동하려는 노선 ID", example = "2") Long lineId,
        @Schema(description = "경로의 directionCode 그대로 전달. headsign이 없으면 필수. UP/DOWN/INNER/OUTER/LOOP", example = "INNER") String direction,
        @Schema(description = "선택. 알고 있는 행선지만 전달. 없으면 생략 또는 null; 임의 생성 금지", example = "사당 방면") String headsign) {

    public record Valid(long stationId, long lineId, DirectionCode direction, String headsign) {}

    /** Validates field formats; direction evidence is required by the service after station lookup. */
    public Valid validate() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (stationId == null || stationId <= 0) errors.put("stationId", "stationId는 1 이상의 정수여야 합니다.");
        if (lineId == null || lineId <= 0) errors.put("lineId", "lineId는 1 이상의 정수여야 합니다.");
        DirectionCode code = null;
        if (direction != null && !direction.isBlank()) {
            code = Arrays.stream(DirectionCode.values()).filter(value -> value.name().equals(direction.strip())).findFirst().orElse(null);
            if (code == null) errors.put("direction", "direction은 UP, DOWN, INNER, OUTER, LOOP 중 하나여야 합니다.");
        }
        String normalizedHeadsign = headsign == null || headsign.isBlank() ? null : headsign.strip();
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        return new Valid(stationId, lineId, code, normalizedHeadsign);
    }
}
