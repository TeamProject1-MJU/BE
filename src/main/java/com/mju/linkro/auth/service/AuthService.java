package com.mju.linkro.auth.service;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.dto.KakaoLoginResponse;
import com.mju.linkro.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
	private final KakaoClient kakaoClient;
	private final UserLoginService userLoginService;
	private final JwtProvider jwtProvider;

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
		return new KakaoLoginResponse(jwtProvider.createAccessToken(user.getId()),
			new KakaoLoginResponse.UserInfo(user.getId(), user.getNickname()), result.isNewUser());
	}
}
