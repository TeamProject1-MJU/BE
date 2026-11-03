package com.mju.linkro.subway.station.controller;

import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.subway.station.dto.StationResponses.*;
import com.mju.linkro.subway.station.service.StationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: 요청 형식·값 오류", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class StationController {
    private final StationQueryService stations;

    @GetMapping("/lines")
    @Operation(summary = "노선 목록", description = "1~8호선. sort_order, id 순.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "노선 배열", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.LineList.class)))
    public ApiResponse<List<Line>> lines() {
        return ApiResponse.success(stations.lines());
    }

    @GetMapping("/stations")
    @Operation(summary = "역 검색", description = "keyword와 bbox 중 하나 이상 필수이며 함께 쓸 수 있습니다. 한 역이 여러 노선에 속하면 lines로 묶어 한 건입니다. "
            + "keyword가 있으면 이름 정확히 일치 → 접두 일치 → 이름 → id 순, bbox만 있으면 id 순. isSupported=false 역도 포함합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역 페이지", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.StationPageResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR: keyword·bbox 모두 없음, keyword 50자 초과, bbox 형식·범위 오류, page<0, size 범위 오류(1~50, bbox가 있으면 1~500)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class),
                    examples = @ExampleObject(value = "{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"요청 값이 올바르지 않습니다.\",\"details\":{\"keyword\":\"keyword 또는 bbox 중 하나 이상이 필요합니다.\"}}}")))
    public ApiResponse<StationPage> search(
            @Parameter(description = "역명 부분 일치(대소문자 무시). 앞뒤 공백 제거, 최대 50자. 끝의 \"역\"을 뗀 이름으로도 찾습니다(강남역 → 강남).", example = "강남")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "이 노선에 속한 역만. 없는 노선이면 빈 결과", example = "2") @RequestParam(required = false) Long lineId,
            @Parameter(description = "minLng,minLat,maxLng,maxLat (WGS84)", example = "126.9,37.4,127.1,37.6") @RequestParam(required = false) String bbox,
            @Parameter(description = "0부터, 기본 0", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "기본 20. keyword만 있으면 최대 50, bbox가 있으면 최대 500", example = "20") @RequestParam(required = false) Integer size) {
        return ApiResponse.success(stations.search(keyword, lineId, bbox, page, size));
    }

    @GetMapping("/stations/{id}")
    @Operation(summary = "역 상세")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "역", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.StationDetail.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STATION_NOT_FOUND", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<Station> station(@PathVariable long id) {
        return ApiResponse.success(stations.station(id));
    }
}
