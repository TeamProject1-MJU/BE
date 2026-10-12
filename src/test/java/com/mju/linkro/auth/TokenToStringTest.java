package com.mju.linkro.auth;

import com.mju.linkro.auth.dto.KakaoLoginResponse;
import com.mju.linkro.auth.dto.TokenResponse;
import com.mju.linkro.auth.service.RefreshTokenService;
import com.mju.linkro.common.exception.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TokenToStringTest {
	private static final String ACCESS = "raw-access-token-value";
	private static final String REFRESH = "raw-refresh-token-value";

	@Test
	void toStringMasksRawTokensButJsonKeepsThem() {
		UUID userId = UUID.randomUUID();
		var login = new KakaoLoginResponse(ACCESS, REFRESH, 900, new KakaoLoginResponse.UserInfo(userId, "인준"), true);
		var tokens = new TokenResponse(ACCESS, REFRESH, 900);
		var rotation = new RefreshTokenService.Rotation(userId, REFRESH, null);

		for (Object value : new Object[] {login, tokens, rotation}) {
			assertThat(value.toString()).doesNotContain(ACCESS, REFRESH).contains("refreshToken=***");
		}
		assertThat(login.toString()).contains("accessToken=***", "accessTokenExpiresIn=900", userId.toString(), "isNewUser=true");
		assertThat(tokens.toString()).contains("accessToken=***", "accessTokenExpiresIn=900");
		assertThat(rotation.toString()).contains(userId.toString());
		assertThat(new RefreshTokenService.Rotation(null, null, ErrorCode.TOKEN_INVALID).toString())
			.isEqualTo("Rotation[userId=null, refreshToken=null, error=TOKEN_INVALID]");

		var json = JsonMapper.builder().build();
		assertThat(json.writeValueAsString(tokens))
			.isEqualTo("{\"accessToken\":\"" + ACCESS + "\",\"refreshToken\":\"" + REFRESH + "\",\"accessTokenExpiresIn\":900}");
		assertThat(json.writeValueAsString(login)).contains("\"accessToken\":\"" + ACCESS + "\"", "\"refreshToken\":\"" + REFRESH + "\"");
	}
}
