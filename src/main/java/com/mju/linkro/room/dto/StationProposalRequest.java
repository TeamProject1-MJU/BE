package com.mju.linkro.room.dto;

import com.mju.linkro.common.exception.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.*;
import tools.jackson.databind.JsonNode;

public record StationProposalRequest(
        @Schema(implementation = Long.class, type = "integer", format = "int64", requiredMode = Schema.RequiredMode.REQUIRED, example = "112") JsonNode stationId,
        @Schema(implementation = String.class, type = "string", format = "uuid", description = "선택 추천 결과 ID. 생략 또는 null이면 직접 제안해요.") JsonNode resultId) {
    public long validatedStationId() {
        return new SelectedStationRequest(stationId).validatedStationId();
    }

    public UUID validatedResultId() {
        if (resultId == null || resultId.isNull()) return null;
        if (resultId.isString()) {
            String value = resultId.asString();
            try {
                UUID parsed = UUID.fromString(value);
                if (parsed.toString().equalsIgnoreCase(value)) return parsed;
            } catch (IllegalArgumentException ignored) { }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("resultId", "UUID 문자열이 필요해요."));
    }
}
