package com.mju.linkro.auth.controller;

import com.mju.linkro.auth.dto.KakaoLoginRequest;
import com.mju.linkro.auth.dto.KakaoLoginResponse;
import com.mju.linkro.auth.dto.RefreshTokenRequest;
import com.mju.linkro.auth.dto.TokenResponse;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.common.response.ApiResponse;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import com.mju.linkro.common.config.ApiDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@SecurityRequirements // POST kakao/refresh/logout are public in SecurityConfig.
@ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "INTERNAL_ERROR", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
})
public class AuthController {
	private final AuthService authService;

	@PostMapping("/kakao")
	@Operation(summary = "카카오 로그인", description = "공개 API. 카카오 Access Token을 본문으로 전달합니다. 응답의 서버 accessToken을 Swagger Authorize에 등록합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Login.class)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "KAKAO_AUTH_FAILED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "EXTERNAL_API_ERROR: 카카오 API 통신 실패", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
	public ApiResponse<KakaoLoginResponse> login(@Valid @RequestBody KakaoLoginRequest request) {
		return ApiResponse.success(authService.login(request.getKakaoAccessToken()));
	}

	@PostMapping("/refresh")
	@Operation(summary = "토큰 갱신", description = "공개 API. Bearer Access Token 대신 본문의 refreshToken을 검증합니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 Access Token과 Refresh Token", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Tokens.class)))
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "TOKEN_INVALID / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.Failure.class)))
	public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return ApiResponse.success(authService.refresh(request.getRefreshToken()));
	}

	@PostMapping("/logout")
	@Operation(summary = "로그아웃", description = "공개 API. 본문의 refreshToken을 폐기합니다. Bearer 인증을 요구하지 않습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 성공. data는 null.", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiDocumentation.EmptySuccess.class), examples = @ExampleObject(value = "{\"success\":true,\"data\":null}")))
	public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.getRefreshToken());
		return ApiResponse.success();
	}
}
