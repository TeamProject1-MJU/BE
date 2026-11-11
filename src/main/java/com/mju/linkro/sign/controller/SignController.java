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
            + "기대 경로와 비교한 CORRECT / WRONG / RETAKE 판정을 그대로 돌려줍니다. 이미지는 저장하지 않습니다. "
            + "route: {\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\",\"headsign\":\"사당 방면\"} (direction은 생략 가능). 응답 목표 5초 이내.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "판정 결과",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.SignCheck.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "IMAGE_INVALID: 이미지 없음·JPG/PNG 아님·5MB 초과 / VALIDATION_ERROR: route 없음·형식 오류, 존재하지 않는 lineId",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STATION_NOT_FOUND",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "AI_QUOTA_EXCEEDED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "AI_UNAVAILABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
    public ApiResponse<SignCheckResponse> directionCheck(
            @RequestPart(value = "image", required = false) MultipartFile image,
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
