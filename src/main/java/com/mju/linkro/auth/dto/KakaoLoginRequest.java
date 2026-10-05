package com.mju.linkro.auth.dto;

import jakarta.validation.constraints.NotBlank;

public final class KakaoLoginRequest {
	@NotBlank
	private String kakaoAccessToken;

	public String getKakaoAccessToken() { return kakaoAccessToken; }
	public void setKakaoAccessToken(String kakaoAccessToken) { this.kakaoAccessToken = kakaoAccessToken; }
}
