package com.mju.linkro.room.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.service.SelectedStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/selected-station")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보충 계약(API v1.12 원문에 미명시): PUT·DELETE 모두 200, data:null.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":null}"))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER / NOT_ROOM_OWNER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ROOM_BUSY", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class SelectedStationController {
    private final SelectedStationService service;

    @PutMapping
    @Operation(summary = "최종 약속역 지정·변경", description = "현재 OWNER 확인 후 stationId를 검증합니다. 후보·투표와 무관하게 지원 역을 지정할 수 있습니다. 같은 역도 confirmedAt을 갱신합니다. 기존 기록을 보존합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = SelectedStationRequest.class), examples = @ExampleObject(value = "{\"stationId\":112}"))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND / STATION_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "STATION_UNSUPPORTED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Void> select(@CurrentUser UUID userId, @PathVariable UUID roomId, @RequestBody SelectedStationRequest request) {
        service.select(roomId, userId, request);
        return ApiResponse.success();
    }

    @DeleteMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @Operation(summary = "최종 약속역 해제", description = "방 락과 현재 OWNER 검사는 항상 수행합니다. 두 필드를 null로 하고 OPEN으로 계산합니다. 보충 계약(API v1.12 원문에 미명시): 이미 미확정이면 상태를 유지하고 200·data:null로 성공합니다. 무변경 시 #43 이벤트·SYSTEM 발행 대상에서 제외합니다. 후보·투표 등 기존 기록은 보존합니다.")
    public ApiResponse<Void> clear(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        service.clear(roomId, userId);
        return ApiResponse.success();
    }
}
