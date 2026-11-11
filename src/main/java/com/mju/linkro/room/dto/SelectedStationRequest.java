package com.mju.linkro.room.dto;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@Schema(name = "SelectedStationRequest")
public record SelectedStationRequest(
        @Schema(implementation = Long.class, type = "integer", format = "int64",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "112",
                description = "필수 Long 정수. null·생략·문자열·소수·범위 초과는 허용하지 않습니다.") JsonNode stationId) {
    public Long validatedStationId() {
        if (stationId == null || !stationId.isIntegralNumber() || !stationId.canConvertToLong())
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    Map.of("stationId", "Long 범위의 JSON 정수가 필요합니다."));
        return stationId.longValue();
    }
}
