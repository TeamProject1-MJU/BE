package com.mju.linkro.sign.dto;

import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import io.swagger.v3.oas.annotations.media.Schema;

/** 9.1 response. {@code reason} is null for CORRECT; {@code recognized} fields are null where the AI read nothing. */
@Schema(name = "SignCheckResponse")
public record SignCheckResponse(SignResult result, SignReason reason, Place recognized, Place expected, String message) {

    public record Place(String stationName, String lineName, String headsign) {}
}
