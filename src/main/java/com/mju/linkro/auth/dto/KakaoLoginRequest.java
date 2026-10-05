package com.mju.linkro.auth.dto;

import jakarta.validation.constraints.NotBlank;

public final class KakaoLoginRequest {
	@NotBlank(message = "카카오 Access Token은 필수입니다.")
	private String kakaoAccessToken;

	public String getKakaoAccessToken() { return kakaoAccessToken; }
	public void setKakaoAccessToken(String kakaoAccessToken) { this.kakaoAccessToken = kakaoAccessToken; }
}
