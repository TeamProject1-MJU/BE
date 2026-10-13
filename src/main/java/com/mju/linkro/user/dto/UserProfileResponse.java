package com.mju.linkro.user.dto;

import com.mju.linkro.user.domain.User;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record UserProfileResponse(UUID id, String nickname, OffsetDateTime createdAt) {
	// API spec: ISO 8601 with the server's Asia/Seoul offset, e.g. 2026-10-02T10:00:00+09:00.
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	public static UserProfileResponse from(User user) {
		return new UserProfileResponse(user.getId(), user.getNickname(),
			user.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).atZone(ZONE).toOffsetDateTime());
	}
}
