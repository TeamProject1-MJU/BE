package com.mju.linkro.user.service;

import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.room.domain.MeetingRoom;
import com.mju.linkro.room.repository.MeetingMemberRepository;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.room.service.RoomLeaveProcessor;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.dto.NicknameUpdateResponse;
import com.mju.linkro.user.dto.UserProfileResponse;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@RequiredArgsConstructor
public class UserService {
	static final int NICKNAME_MIN_LENGTH = 2;
	static final int NICKNAME_MAX_LENGTH = 20;
	static final String NICKNAME_MESSAGE = "닉네임은 앞뒤 공백을 제외하고 2~20자여야 합니다.";

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final UserAccess userAccess;
	private final MeetingMemberRepository members;
	private final RoomAccess roomAccess;
	private final RoomLeaveProcessor leaveProcessor;
	private final RoomEvents events;

	@Transactional(readOnly = true)
	public UserProfileResponse getMe(UUID userId) {
		return UserProfileResponse.from(activeUser(userId));
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public NicknameUpdateResponse updateNickname(UUID userId, String nickname) {
		// The account check comes first so a withdrawn user always gets TOKEN_INVALID.
		User user = userAccess.lockActive(userId);
		user.changeNickname(normalizeNickname(nickname));
		return NicknameUpdateResponse.from(user);
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void withdraw(UUID userId) {
		User user = userAccess.lockActive(userId);
		var locked = new ArrayList<MeetingRoom>();
		// Keep the DB UUID order; acquire every lock before the processor can mutate anything.
		for (UUID id : members.findRoomIdsForWithdrawal(userId)) {
			roomAccess.lockForWithdrawal(id).ifPresent(locked::add);
		}
		for (var room : locked) {
			// A regular departure may have committed between the list query and room lock.
			if (members.findCurrentRole(room.getId(), userId).isPresent()) {
				leaveProcessor.leaveLocked(room, userId);
			}
		}
		user.softDelete();
		refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
		// The room events (MEMBER_LEFT, ...) came from leaveLocked; after the commit this user's connections are closed.
		events.disconnect(userId);
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
		if (stripped.indexOf(0) >= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR,
				Map.of("nickname", NICKNAME_MESSAGE + " NUL 문자는 사용할 수 없습니다."));
		}
		int length = stripped.codePointCount(0, stripped.length());
		if (length < NICKNAME_MIN_LENGTH || length > NICKNAME_MAX_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, Map.of("nickname", NICKNAME_MESSAGE));
		}
		return stripped;
	}
}
