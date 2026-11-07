package com.mju.linkro.route.controller;

import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.route.dto.RouteSearchRequest;
import com.mju.linkro.route.dto.RouteSearchResponse;
import com.mju.linkro.route.service.RouteSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class RouteController {
    private final RouteSearchService routes;

    @PostMapping("/routes/search")
    @Operation(summary = "지하철 경로 검색", description = "카카오 대중교통 길찾기의 지하철 경로(버스 포함 경로 제외)에 우리 DB 혼잡도를 붙입니다. "
            + "카카오 키 없음·호출 실패(3초×재시도 1회, 전체 5초)·지하철 경로 없음·역 매칭 실패면 내부 다익스트라 경로(routeSource=INTERNAL, fareSource=ESTIMATED)로 대체합니다. "
            + "roomId를 보내면 방 멤버를 확인하고, 약속 시각이 있으면 약속 시각 − 소요시간을 혼잡도 기준 출발 시각으로 씁니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RouteSearchRequest.class),
                            examples = { @ExampleObject(name = "혼자 이동", value = "{\"originStationId\":31,\"destinationStationId\":35}"),
                                    @ExampleObject(name = "방 화면", value = "{\"originStationId\":31,\"destinationStationId\":35,\"roomId\":\"0192f3a4-8c2d-7d4f-8b21-6e3f9a7c2b02\"}") })))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "경로 목록. INTERNAL이면 경로 1개",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.RouteSearch.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 역 ID 없음·1 미만, departAt 형식 오류, roomId가 UUID가 아님, 본문 형식 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "NOT_ROOM_MEMBER: roomId를 보냈는데 그 방 멤버가 아님",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ROOM_NOT_FOUND(roomId의 방 없음) / STATION_NOT_FOUND / ROUTE_NOT_FOUND(내부 그래프로도 경로 없음)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "STATION_UNSUPPORTED: 출발·도착역이 isSupported=false",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<RouteSearchResponse> search(@CurrentUser UUID userId, @RequestBody RouteSearchRequest request) {
        return ApiResponse.success(routes.search(userId, request));
    }
}
