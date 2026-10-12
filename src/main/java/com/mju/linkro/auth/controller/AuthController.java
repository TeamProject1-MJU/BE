package com.mju.linkro.auth.controller;

import com.mju.linkro.auth.dto.KakaoLoginRequest;
import com.mju.linkro.auth.dto.KakaoLoginResponse;
import com.mju.linkro.auth.dto.RefreshTokenRequest;
import com.mju.linkro.auth.dto.TokenResponse;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService authService;

	@PostMapping("/kakao")
	public ApiResponse<KakaoLoginResponse> login(@Valid @RequestBody KakaoLoginRequest request) {
		return ApiResponse.success(authService.login(request.getKakaoAccessToken()));
	}

	@PostMapping("/refresh")
	public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return ApiResponse.success(authService.refresh(request.getRefreshToken()));
	}

	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.getRefreshToken());
		return ApiResponse.success();
	}
}
