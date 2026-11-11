package com.mju.linkro.recommendation.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/recommendations")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class RecommendationController {
    private final RecommendationService recommendations;

    @PostMapping
    @Operation(summary = "중간역 추천 생성", description = "참여자 전원의 출발역 기준으로 시간·요금·거리 기준별 최적 중간역과 주변 대안역을 계산해 저장합니다. "
            + "멤버가 2명 미만이면 TOO_FEW_MEMBERS, 출발역이 없는 멤버가 있으면 DEPARTURE_MISSING(error.details.missingUserIds), 약속 시각 전 3시간 동안 어느 노선도 운행하지 않으면(열차 시각표 기준, 예: 04:00) NO_SERVICE_TIME으로 요청 전체를 거절하며 실행 기록을 남기지 않습니다. "
            + "conditionText는 AI로 filter 항목으로 해석하고(실패하면 filter만 적용하고 message로 알림), 조건에 맞는 역이 없으면 200과 results: []입니다. "
            + "body는 생략할 수 있습니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = RecommendationRequest.class))))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 결과(결과 없음도 200)", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RecommendationResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: filter 범위, memberLimits의 userId가 방 멤버가 아님", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "TOO_FEW_MEMBERS / DEPARTURE_MISSING / NO_SERVICE_TIME / STATION_UNSUPPORTED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<RecommendationResponse> create(@CurrentUser UUID userId, @PathVariable UUID roomId,
                                                      @RequestBody(required = false) RecommendationRequest request) {
        return ApiResponse.success(recommendations.create(userId, roomId, request));
    }

    @GetMapping("/latest")
    @Operation(summary = "최근 추천 결과 조회", description = "가장 최근 실행(created_at DESC, id DESC)을 8.1과 같은 구조로 돌려줍니다. isStale·staleReasons는 조회할 때 계산합니다. 실행 기록이 없으면 data: null.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 결과 또는 data: null", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RecommendationResponse.class)))
    public ApiResponse<RecommendationResponse> latest(@CurrentUser UUID userId, @PathVariable UUID roomId) {
        return ApiResponse.success(recommendations.latest(userId, roomId));
    }

    @GetMapping("/{runId}")
    @Operation(summary = "특정 추천 실행 조회", description = "isStale·staleReasons는 그 방의 가장 최근 실행일 때만 계산하고, 이전 실행이면 둘 다 null입니다. 다른 방의 실행이면 RUN_NOT_FOUND(404).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 결과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = RecommendationResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RUN_NOT_FOUND / ROOM_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<RecommendationResponse> byRun(@CurrentUser UUID userId, @PathVariable UUID roomId, @PathVariable UUID runId) {
        return ApiResponse.success(recommendations.byRun(userId, roomId, runId));
    }
}
