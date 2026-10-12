package com.mju.linkro.auth.dto;

public record TokenResponse(String accessToken, String refreshToken, long accessTokenExpiresIn) {
	/** Masks raw tokens so they never reach logs; JSON uses the accessors and is unaffected. */
	@Override
	public String toString() {
		return "TokenResponse[accessToken=***, refreshToken=***, accessTokenExpiresIn=" + accessTokenExpiresIn + "]";
	}
}
