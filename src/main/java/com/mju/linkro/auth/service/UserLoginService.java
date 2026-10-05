package com.mju.linkro.auth.service;

import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserLoginService {
	private final UserRepository userRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoginResult login(KakaoUserInfo info) {
		return userRepository.findByKakaoId(info.kakaoId())
			.map(this::existing)
			.orElseGet(() -> new LoginResult(userRepository.saveAndFlush(
				new User(info.kakaoId(), initialNickname(info.nickname()))), true));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public LoginResult loginExisting(String kakaoId) {
		return userRepository.findByKakaoId(kakaoId).map(this::existing).orElse(null);
	}

	private LoginResult existing(User user) {
		if (user.getDeletedAt() != null) {
			user.restore();
		}
		return new LoginResult(user, false);
	}

	private String initialNickname(String nickname) {
		if (nickname == null || nickname.isBlank()) {
			return "사용자" + ThreadLocalRandom.current().nextInt(1000, 10000);
		}
		int length = nickname.codePointCount(0, nickname.length());
		return nickname.substring(0, nickname.offsetByCodePoints(0, Math.min(length, 20)));
	}

	public record LoginResult(User user, boolean isNewUser) { }
}
