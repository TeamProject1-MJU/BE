package com.mju.linkro.subway.congestion.controller;

import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.subway.congestion.dto.CongestionQuery;
import com.mju.linkro.subway.congestion.dto.CongestionResponse;
import com.mju.linkro.subway.congestion.service.CongestionQueryService;
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
public class CongestionController {
    private final CongestionQueryService congestion;

    @GetMapping("/congestion")
    @Operation(summary = "역 혼잡도", description = "한 역·노선·방향의 30분 슬롯 혼잡도(열차 단위). 시각은 Asia/Seoul. 슬롯은 05:30~23:30과 00:00, 00:30(39칸). "
            + "date가 있으면 00:00~00:59는 전날 영업일, 01:00~05:29는 같은 날 05:30(첫차), 막차 이후면 다음 영업일 05:30(첫차)으로 조회하고 "
            + "dayType은 영업일로 계산합니다(공휴일 SUNDAY). date가 없으면 dayType을 영업일의 요일 유형으로 보고, 다음 영업일이 필요한 경우는 데이터 없음입니다. "
            + "같은 슬롯에 여러 출처가 있으면 PREDICTED > ESTIMATED > MOCK. 데이터가 없으면 supported=false(회색).")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "혼잡도. 데이터가 없어도 200(supported=false)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Congestion.class), examples = {
                    @ExampleObject(name = "예측값", value = "{\"success\":true,\"data\":{\"stationId\":31,\"lineId\":2,\"direction\":\"INNER\",\"dayType\":\"WEEKDAY\",\"slotStart\":\"18:30\",\"slotDate\":null,\"firstTrain\":false,\"supported\":true,\"pct\":93,\"level\":\"NORMAL\",\"levelLabel\":\"보통\",\"source\":\"PREDICTED\"}}"),
                    @ExampleObject(name = "추정값(성수 외선)", value = "{\"success\":true,\"data\":{\"stationId\":20,\"lineId\":2,\"direction\":\"OUTER\",\"dayType\":\"WEEKDAY\",\"slotStart\":\"08:00\",\"slotDate\":\"2026-11-06\",\"firstTrain\":false,\"supported\":true,\"pct\":62,\"level\":\"NORMAL\",\"levelLabel\":\"보통\",\"source\":\"ESTIMATED\"}}"),
                    @ExampleObject(name = "막차 이후 첫차", value = "{\"success\":true,\"data\":{\"stationId\":31,\"lineId\":2,\"direction\":\"INNER\",\"dayType\":\"SATURDAY\",\"slotStart\":\"05:30\",\"slotDate\":\"2026-11-07\",\"firstTrain\":true,\"supported\":true,\"pct\":12,\"level\":\"COMFORTABLE\",\"levelLabel\":\"쾌적\",\"source\":\"PREDICTED\"}}"),
                    @ExampleObject(name = "데이터 없음", value = "{\"success\":true,\"data\":{\"stationId\":31,\"lineId\":2,\"direction\":\"INNER\",\"dayType\":\"WEEKDAY\",\"slotStart\":\"13:00\",\"slotDate\":null,\"firstTrain\":false,\"supported\":false,\"pct\":null,\"level\":null,\"levelLabel\":null,\"source\":null}}")}))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 필수값 없음, id가 1 이상의 정수가 아님, direction·dayType 값 오류, time이 HH:mm이 아님, date가 yyyy-MM-dd가 아님, date 없이 dayType 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class),
                    examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"요청 값이 올바르지 않습니다.\",\"details\":{\"time\":\"time은 HH:mm 형식이어야 합니다.\"}}}")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STATION_NOT_FOUND: 역 없음 / NOT_FOUND: 지원 역인데 그 노선에 속하지 않음(details.lineId)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<CongestionResponse> congestion(
            @Parameter(description = "역 id", example = "31") @RequestParam(required = false) String stationId,
            @Parameter(description = "노선 id", example = "2") @RequestParam(required = false) String lineId,
            @Parameter(description = "열차 방향", example = "INNER", schema = @Schema(allowableValues = {"UP", "DOWN", "INNER", "OUTER", "LOOP"}))
            @RequestParam(required = false) String direction,
            @Parameter(description = "영업일의 요일 유형. date가 없으면 필수, 있으면 무시(형식만 검사)", example = "WEEKDAY",
                    schema = @Schema(allowableValues = {"WEEKDAY", "SATURDAY", "SUNDAY"}))
            @RequestParam(required = false) String dayType,
            @Parameter(description = "통과 시각 HH:mm (Asia/Seoul)", example = "18:40") @RequestParam(required = false) String time,
            @Parameter(description = "선택. 통과 시각의 달력 날짜 yyyy-MM-dd", example = "2026-11-06") @RequestParam(required = false) String date) {
        return ApiResponse.success(congestion.congestion(CongestionQuery.of(stationId, lineId, direction, dayType, time, date)));
    }
}
