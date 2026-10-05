package com.mju.linkro.auth.dto;

import java.util.UUID;

public record KakaoLoginResponse(String accessToken, UserInfo user, boolean isNewUser) {
	public record UserInfo(UUID id, String nickname) {
	}
}
