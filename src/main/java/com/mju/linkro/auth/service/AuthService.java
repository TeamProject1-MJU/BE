package com.mju.linkro.auth.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.dto.KakaoLoginResponse;
import com.mju.linkro.auth.dto.TokenResponse;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
	private final KakaoClient kakaoClient;
	private final UserLoginService userLoginService;
	private final JwtProvider jwtProvider;
	private final RefreshTokenService refreshTokenService;

	public KakaoLoginResponse login(String kakaoAccessToken) {
		var info = kakaoClient.getUserInfo(kakaoAccessToken);
		UserLoginService.LoginResult result;
		try {
			result = userLoginService.login(info);
		} catch (DataIntegrityViolationException exception) {
			// The failed insert transaction has ended before reading the winning row.
			result = userLoginService.loginExisting(info.kakaoId());
			if (result == null) {
				throw exception;
			}
		}
		var user = result.user();
		// Every login starts a new token family.
		String refreshToken = refreshTokenService.issue(user.getId(), UuidCreator.getTimeOrderedEpoch());
		return new KakaoLoginResponse(jwtProvider.createAccessToken(user.getId()), refreshToken,
			jwtProvider.accessTokenExpiresIn(), new KakaoLoginResponse.UserInfo(user.getId(), user.getNickname()),
			result.isNewUser());
	}

	public TokenResponse refresh(String refreshToken) {
		var rotation = refreshTokenService.rotate(refreshToken);
		// Thrown after the rotation transaction has committed any family revocation.
		if (rotation.error() != null) {
			throw new BusinessException(rotation.error());
		}
		return new TokenResponse(jwtProvider.createAccessToken(rotation.userId()), rotation.refreshToken(),
			jwtProvider.accessTokenExpiresIn());
	}

	public void logout(String refreshToken) {
		refreshTokenService.revokeFamily(refreshToken);
	}
}
