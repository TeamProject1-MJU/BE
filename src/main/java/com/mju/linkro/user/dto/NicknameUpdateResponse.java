package com.mju.linkro.user.dto;

import com.mju.linkro.user.domain.User;
import java.util.UUID;

public record NicknameUpdateResponse(UUID id, String nickname) {
	public static NicknameUpdateResponse from(User user) {
		return new NicknameUpdateResponse(user.getId(), user.getNickname());
	}
}
