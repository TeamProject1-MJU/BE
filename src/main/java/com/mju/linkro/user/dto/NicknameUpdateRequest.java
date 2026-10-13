package com.mju.linkro.user.dto;

/** Validated in UserService, which counts code points after stripping surrounding whitespace. */
public final class NicknameUpdateRequest {
	private String nickname;

	public String getNickname() { return nickname; }
	public void setNickname(String nickname) { this.nickname = nickname; }
}
