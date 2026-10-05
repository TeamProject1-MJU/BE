package com.mju.linkro.auth.controller;

import com.mju.linkro.auth.dto.KakaoLoginRequest;
import com.mju.linkro.auth.dto.KakaoLoginResponse;
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
}
