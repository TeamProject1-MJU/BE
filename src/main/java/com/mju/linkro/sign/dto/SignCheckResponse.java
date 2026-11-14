package com.mju.linkro.sign.dto;

import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** 9.1 response. {@code reason} is null for CORRECT; {@code recognized} fields are null where the AI read nothing. */
@Schema(name = "SignCheckResponse")
public record SignCheckResponse(SignResult result, SignReason reason, Place recognized, Place expected, String message) {

    public record Place(String stationName, String lineName,
                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                        @Schema(description = "읽은 방면 또는 방향 표시. expected에서 행선지를 모르면 명시적 null", nullable = true)
                        String headsign) {}
}
