package com.mju.linkro.subway.arrival.controller;

import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.subway.arrival.ArrivalService;
import com.mju.linkro.subway.arrival.dto.ArrivalQuery;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
public class ArrivalController {
    private final ArrivalService arrivals;

    @GetMapping("/stations/{stationId}/arrivals")
    @Operation(summary = "실시간 열차 도착 정보", description = "서울 열린데이터광장 실시간 도착정보를 역·노선(·방향)으로 걸러 다음 열차를 돌려줍니다. "
            + "방향은 nextStationId(권장)로 맞추고, 없으면 directionCode, 둘 다 없으면 그 노선의 모든 열차입니다. "
            + "secondsUntil은 외부 수신 시각(recptnDt)부터 지난 시간을 빼서 보정하고, 5분 넘은 정보는 뺍니다. "
            + "외부 응답은 역·노선별로 15초 캐시합니다. 외부 호출이 실패해도 200이며 available=false와 reason을 줍니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "도착 정보. 쓸 수 없으면 available=false",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Arrival.class), examples = {
                    @ExampleObject(name = "정상", value = "{\"success\":true,\"data\":{\"available\":true,\"reason\":null,\"receivedAt\":\"2026-11-10T08:00:05+09:00\",\"trains\":[{\"destinationName\":\"장암\",\"directionCode\":\"UP\",\"secondsUntil\":0,\"message\":\"건대입구 도착\",\"lastTrain\":false},{\"destinationName\":\"장암\",\"directionCode\":\"UP\",\"secondsUntil\":165,\"message\":\"2분 50초 후\",\"lastTrain\":false}]}}"),
                    @ExampleObject(name = "데이터 없음", value = "{\"success\":true,\"data\":{\"available\":false,\"reason\":\"NO_DATA\",\"receivedAt\":\"2026-11-10T08:00:05+09:00\",\"trains\":[]}}"),
                    @ExampleObject(name = "외부 오류", value = "{\"success\":true,\"data\":{\"available\":false,\"reason\":\"UPSTREAM_ERROR\",\"receivedAt\":null,\"trains\":[]}}"),
                    @ExampleObject(name = "미지원 노선", value = "{\"success\":true,\"data\":{\"available\":false,\"reason\":\"UNSUPPORTED_STATION\",\"receivedAt\":null,\"trains\":[]}}")}))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: lineId 없음, id가 1 이상의 정수가 아님, directionCode 값 오류, limit 1~10 밖",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class),
                    examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"요청 값이 올바르지 않습니다.\",\"details\":{\"lineId\":\"lineId가 필요합니다.\"}}}")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STATION_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<ArrivalResponse> arrivals(
            @Parameter(description = "역 id", example = "21") @PathVariable long stationId,
            @Parameter(description = "노선 id(GET /lines의 id). 필수", example = "7") @RequestParam(required = false) String lineId,
            @Parameter(description = "선택(권장). 같은 노선의 다음 역 id. 그 방향 열차만", example = "204") @RequestParam(required = false) String nextStationId,
            @Parameter(description = "선택. nextStationId가 없을 때 방향. LOOP는 방향을 거르지 않음",
                    schema = @Schema(allowableValues = {"UP", "DOWN", "INNER", "OUTER", "LOOP"})) @RequestParam(required = false) String directionCode,
            @Parameter(description = "기본 3, 최대 10", example = "3") @RequestParam(required = false) String limit) {
        return ApiResponse.success(arrivals.arrivals(stationId, ArrivalQuery.of(lineId, nextStationId, directionCode, limit)));
    }
}
