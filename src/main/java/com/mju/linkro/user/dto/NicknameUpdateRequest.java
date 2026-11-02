package com.mju.linkro.user.dto;

/** Validated in UserService, which counts code points after stripping surrounding whitespace. */
public final class NicknameUpdateRequest {
	@io.swagger.v3.oas.annotations.media.Schema(requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED,
		description = "필수이며 null 불가. 앞뒤 strip 후 유니코드 코드포인트 2~20자.", example = "선호")
	private String nickname;

	public String getNickname() { return nickname; }
	public void setNickname(String nickname) { this.nickname = nickname; }
}
