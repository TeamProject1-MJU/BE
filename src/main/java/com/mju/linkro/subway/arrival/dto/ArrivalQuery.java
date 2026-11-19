package com.mju.linkro.subway.arrival.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated query of GET /stations/{id}/arrivals. Invalid fields are reported at once as VALIDATION_ERROR (400). */
public record ArrivalQuery(long lineId, Long nextStationId, DirectionCode directionCode, int limit, Long alightStationId,
                           Long trainAlightStationId) {
    public static final int DEFAULT_LIMIT = 3;
    public static final int MAX_LIMIT = 10;

    /** Without the destination parameters (#174): the filters of #86 only. */
    public static ArrivalQuery of(String lineId, String nextStationId, String directionCode, String limit) {
        return of(lineId, nextStationId, directionCode, limit, null);
    }

    /** @param alightStationId the station the first ride ends at (#174, optional): drops trains of the other branch */
    public static ArrivalQuery of(String lineId, String nextStationId, String directionCode, String limit, String alightStationId) {
        return of(lineId, nextStationId, directionCode, limit, alightStationId, null);
    }

    /**
     * @param trainAlightStationId the station the first train is left at (#174, optional, steps[].trainAlightStationId of
     *                             the route): also drops trains ending before it
     */
    public static ArrivalQuery of(String lineId, String nextStationId, String directionCode, String limit, String alightStationId,
                                  String trainAlightStationId) {
        Map<String, String> errors = new LinkedHashMap<>();
        Long line = null;
        if (lineId == null || lineId.isBlank()) {
            errors.put("lineId", "lineId가 필요합니다.");
        } else {
            line = positive(lineId, "lineId", errors);
        }
        Long next = nextStationId == null || nextStationId.isBlank() ? null : positive(nextStationId, "nextStationId", errors);
        Long alight = alightStationId == null || alightStationId.isBlank() ? null : positive(alightStationId, "alightStationId", errors);
        Long trainAlight = trainAlightStationId == null || trainAlightStationId.isBlank() ? null
                : positive(trainAlightStationId, "trainAlightStationId", errors);
        DirectionCode direction = null;
        if (directionCode != null && !directionCode.isBlank()) {
            try {
                direction = DirectionCode.valueOf(directionCode.strip());
            } catch (IllegalArgumentException exception) {
                errors.put("directionCode", "directionCode는 " + String.join("/", Arrays.stream(DirectionCode.values()).map(Enum::name).toList()) + " 중 하나여야 합니다.");
            }
        }
        int size = DEFAULT_LIMIT;
        if (limit != null && !limit.isBlank()) {
            try {
                size = Integer.parseInt(limit.strip());
            } catch (NumberFormatException exception) {
                size = -1;
            }
            if (size < 1 || size > MAX_LIMIT) errors.put("limit", "limit는 1~" + MAX_LIMIT + "이어야 합니다.");
        }
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, errors);
        return new ArrivalQuery(line, next, direction, size, alight, trainAlight);
    }

    private static Long positive(String raw, String field, Map<String, String> errors) {
        try {
            long value = Long.parseLong(raw.strip());
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        errors.put(field, field + "는 1 이상의 정수여야 합니다.");
        return null;
    }
}
