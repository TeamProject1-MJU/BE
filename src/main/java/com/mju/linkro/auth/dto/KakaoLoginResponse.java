package com.mju.linkro.auth.dto;

import java.util.UUID;

public record KakaoLoginResponse(String accessToken, String refreshToken, long accessTokenExpiresIn,
	UserInfo user, boolean isNewUser) {
	public record UserInfo(UUID id, String nickname) {
	}

	/** Masks raw tokens so they never reach logs; JSON uses the accessors and is unaffected. */
	@Override
	public String toString() {
		return "KakaoLoginResponse[accessToken=***, refreshToken=***, accessTokenExpiresIn=" + accessTokenExpiresIn
			+ ", user=" + user + ", isNewUser=" + isNewUser + "]";
	}
}
