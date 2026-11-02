package com.mju.linkro.room.dto;

import com.mju.linkro.common.exception.*;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public final class RoomMembershipRequests {
    private RoomMembershipRequests() {}
    @Schema(name = "RoomJoinRequest")
    public record Join(@Schema(implementation = String.class, type = "string", requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^[A-Z0-9]{6}$", minLength = 6, maxLength = 6, example = "A3K9PZ") JsonNode roomCode) {
        public String validatedCode() {
            if (roomCode == null || !roomCode.isString() || !roomCode.asString().matches("[A-Z0-9]{6}"))
                throw invalid("roomCode", "대문자 영문·숫자 6자 필수. 공백·소문자는 변환하지 않습니다.");
            return roomCode.asString();
        }
    }
    @Schema(name = "RoomDepartureUpdateRequest")
    public record Departure(@Schema(implementation = Long.class, type = "integer", format = "int64", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "105", description = "필수 Long 정수. null 및 문자열 불가. 해제는 지원하지 않습니다.") JsonNode departureStationId) {
        public Long validatedStationId() {
            if (departureStationId == null || !departureStationId.isIntegralNumber() || !departureStationId.canConvertToLong())
                throw invalid("departureStationId", "Long 범위의 JSON 정수가 필요합니다.");
            return departureStationId.longValue();
        }
    }
    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of(field, message));
    }
}
