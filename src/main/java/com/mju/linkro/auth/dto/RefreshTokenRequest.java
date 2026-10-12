package com.mju.linkro.auth.dto;

import jakarta.validation.constraints.NotBlank;

public final class RefreshTokenRequest {
	@NotBlank(message = "Refresh Token은 필수입니다.")
	private String refreshToken;

	public String getRefreshToken() { return refreshToken; }
	public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
