package com.mju.linkro.user.service;

import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.dto.NicknameUpdateResponse;
import com.mju.linkro.user.dto.UserProfileResponse;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
	static final int NICKNAME_MIN_LENGTH = 2;
	static final int NICKNAME_MAX_LENGTH = 20;
	static final String NICKNAME_MESSAGE = "닉네임은 앞뒤 공백을 제외하고 2~20자여야 합니다.";

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	@Transactional(readOnly = true)
	public UserProfileResponse getMe(UUID userId) {
		return UserProfileResponse.from(activeUser(userId));
	}

	@Transactional
	public NicknameUpdateResponse updateNickname(UUID userId, String nickname) {
		// The account check comes first so a withdrawn user always gets TOKEN_INVALID.
		User user = activeUser(userId);
		user.changeNickname(normalizeNickname(nickname));
		return NicknameUpdateResponse.from(user);
	}

	@Transactional
	public void withdraw(UUID userId) {
		User user = activeUser(userId);
		// 방 기능 구현 시 7.8 나가기 절차를 이 앞에서 호출한다.
		user.softDelete();
		refreshTokenRepository.revokeAllByUserId(user.getId(), Instant.now());
	}

	/** A valid access token for a withdrawn or missing user is treated like an invalid token. */
	private User activeUser(UUID userId) {
		return userRepository.findById(userId)
			.filter(user -> user.getDeletedAt() == null)
			.orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_INVALID));
	}

	// Code points, matching the 20-character limit applied to Kakao nicknames at sign-up.
	private static String normalizeNickname(String nickname) {
		String stripped = nickname == null ? "" : nickname.strip();
		int length = stripped.codePointCount(0, stripped.length());
		if (length < NICKNAME_MIN_LENGTH || length > NICKNAME_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("nickname", NICKNAME_MESSAGE));
		}
		return stripped;
	}
}
