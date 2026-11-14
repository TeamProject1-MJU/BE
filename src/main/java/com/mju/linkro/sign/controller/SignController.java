package com.mju.linkro.sign.controller;

import com.mju.linkro.common.config.ApiDocumentation;
import com.mju.linkro.common.config.OpenApiConfig;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.sign.dto.SignCheckResponse;
import com.mju.linkro.sign.dto.SignRouteRequest;
import com.mju.linkro.sign.service.SignCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class SignController {
    private final SignCheckService signs;
    private final JsonMapper json;

    @PostMapping(value = "/ai/direction-check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "표지판 방향 확인", description = "image(JPG/PNG, 5MB 이하)와 route(JSON 문자열)를 받아 AI가 표지판의 역명·노선·방면을 읽고 "
            + "기대 경로와 비교한 CORRECT / WRONG / RETAKE 판정을 돌려줍니다. 이미지는 저장하지 않습니다. "
            + "route: {\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\"}. headsign이 없으면 direction 필수이며 경로의 directionCode를 그대로 전달합니다. "
            + "headsign이 있으면 direction 생략 가능. 미상 expected.headsign은 null. 정보 부족은 RETAKE/MISSING_INFO(200), 잘못된 경로는 VALIDATION_ERROR(400). 응답 목표 5초 이내.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판정 결과",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.SignCheck.class), examples = {
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "headsign 없는 정상 판정", value = """
                            {"success":true,"data":{"result":"CORRECT","reason":null,
                             "recognized":{"stationName":"강남","lineName":"2호선","headsign":"내선순환"},
                             "expected":{"stationName":"강남","lineName":"2호선","headsign":null},
                             "message":"올바른 방향입니다."}}
                            """),
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "방향 정보 부족", value = """
                            {"success":true,"data":{"result":"RETAKE","reason":"MISSING_INFO",
                             "recognized":{"stationName":"강남","lineName":"2호선","headsign":"성수 방면"},
                             "expected":{"stationName":"강남","lineName":"2호선","headsign":null},
                             "message":"경로와 표지판의 방향을 비교할 정보가 부족합니다. 방향 표시가 보이게 다시 촬영해 주세요."}}
                            """)
            }))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "IMAGE_INVALID: 이미지 없음·JPG/PNG 아님·5MB 초과·손상 / VALIDATION_ERROR: route 형식·필수 입력 오류, 존재하지 않는 lineId, headsign 없는 요청의 잘못된 역·노선·방향 조합",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class), examples =
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "headsign과 direction 모두 누락", value = """
                            {"success":false,"error":{"code":"VALIDATION_ERROR","message":"요청 값이 올바르지 않습니다.",
                             "details":{"direction":"headsign이 없으면 direction은 필수입니다."}}}
                            """)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STATION_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "AI_QUOTA_EXCEEDED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<SignCheckResponse> directionCheck(
            @RequestPart(value = "image", required = false) MultipartFile image,
            @io.swagger.v3.oas.annotations.Parameter(description = "route JSON 문자열. ID는 실제 경로 검색 결과 사용", examples = {
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "headsign 없음", value = "{\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\"}"),
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "기존 headsign 요청", value = "{\"stationId\":105,\"lineId\":2,\"headsign\":\"사당 방면\"}"),
                    @io.swagger.v3.oas.annotations.media.ExampleObject(name = "잘못된 요청: 방향 모두 누락", value = "{\"stationId\":105,\"lineId\":2}")
            })
            @RequestPart(value = "route", required = false) String route) {
        return ApiResponse.success(signs.check(bytes(image), parse(route)));
    }

    private static byte[] bytes(MultipartFile image) {
        if (image == null) throw new BusinessException(ErrorCode.IMAGE_INVALID);
        try {
            return image.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.IMAGE_INVALID);
        }
    }

    private SignRouteRequest parse(String route) {
        SignRouteRequest request = null;
        try {
            if (route != null) request = json.readValue(route, SignRouteRequest.class);
        } catch (JacksonException exception) {
            // falls through to the same error as a missing part
        }
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("route", "route는 JSON 객체여야 합니다."));
        }
        return request;
    }
}
