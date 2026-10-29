package com.mju.linkro.auth;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.domain.RefreshToken;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full context, so requests pass through the real JWT filter without an Authorization header. */
@SpringBootTest
@ActiveProfiles("test")
class AuthControllerRefreshTest {
	@Autowired WebApplicationContext context;
	@Autowired UserRepository users;
	@Autowired RefreshTokenRepository refreshTokens;
	@org.springframework.beans.factory.annotation.Value("${auth.jwt.secret}") String jwtSecret;
	@MockitoBean KakaoClient kakaoClient;
	MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).build();
		refreshTokens.deleteAll();
		users.deleteAll();
	}

	@Test
	void loginRefreshLogoutIgnoreExpiredAccessTokenHeader() throws Exception {
		String expired = io.jsonwebtoken.Jwts.builder().subject(UUID.randomUUID().toString())
			.expiration(java.util.Date.from(Instant.now().minusSeconds(60)))
			.signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(io.jsonwebtoken.io.Decoders.BASE64.decode(jwtSecret)),
				io.jsonwebtoken.Jwts.SIG.HS256).compact();
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "tester"));
		var login = data(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/auth/kakao").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
			.contentType(MediaType.APPLICATION_JSON).content("{\"kakaoAccessToken\":\"test-token\"}"))
			.andExpect(status().isOk()));
		var refreshed = data(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/auth/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
			.contentType(MediaType.APPLICATION_JSON).content(body(login.get("refreshToken").asString())))
			.andExpect(status().isOk()));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + expired).contentType(MediaType.APPLICATION_JSON)
			.content(body(refreshed.get("refreshToken").asString())))
			.andExpect(status().isOk());
		assertFailure(post("/api/v1/auth/refresh", body(refreshed.get("refreshToken").asString())), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void loginRefreshLogoutWorkWithoutAuthorizationHeader() throws Exception {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		var login = data(post("/api/v1/auth/kakao", "{\"kakaoAccessToken\":\"test-token\"}")
			.andExpect(status().isOk()));
		assertThat(login.get("refreshToken").asString()).isNotBlank();
		assertThat(login.get("accessTokenExpiresIn").asLong()).isEqualTo(900);

		var refreshed = post("/api/v1/auth/refresh", body(login.get("refreshToken").asString()))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		var data = data(refreshed);
		refreshed.andExpect(content().json("{\"success\":true,\"data\":{\"accessToken\":\"" + data.get("accessToken").asString()
			+ "\",\"refreshToken\":\"" + data.get("refreshToken").asString() + "\",\"accessTokenExpiresIn\":900}}",
			JsonCompareMode.STRICT));

		post("/api/v1/auth/logout", body(data.get("refreshToken").asString()))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":null}", JsonCompareMode.STRICT));
		assertFailure(post("/api/v1/auth/refresh", body(data.get("refreshToken").asString())), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void unknownRefreshTokenReturnsTokenInvalid() throws Exception {
		assertFailure(post("/api/v1/auth/refresh", body("unknown-token")), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void expiredRefreshTokenReturnsTokenExpired() throws Exception {
		var user = users.saveAndFlush(new User("123", "인준"));
		refreshTokens.saveAndFlush(new RefreshToken(user.getId(), UUID.randomUUID(), sha256("expired-token"),
			Instant.now().minusSeconds(1)));
		assertFailure(post("/api/v1/auth/refresh", body("expired-token")), ErrorCode.TOKEN_EXPIRED);
	}

	@Test
	void reusedRefreshTokenReturnsTokenInvalid() throws Exception {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		String token = data(post("/api/v1/auth/kakao", "{\"kakaoAccessToken\":\"test-token\"}")).get("refreshToken").asString();
		post("/api/v1/auth/refresh", body(token)).andExpect(status().isOk());
		assertFailure(post("/api/v1/auth/refresh", body(token)), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void logoutWithUnknownTokenReturnsOk() throws Exception {
		post("/api/v1/auth/logout", body("unknown-token"))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":null}", JsonCompareMode.STRICT));
	}

	@Test
	void blankRefreshTokenIsValidationErrorOnBothEndpoints() throws Exception {
		for (String path : new String[] {"/api/v1/auth/refresh", "/api/v1/auth/logout"}) {
			post(path, "{\"refreshToken\":\" \"}")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentType(MediaType.APPLICATION_JSON))
				.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\","
					+ "\"message\":\"" + ErrorCode.VALIDATION_ERROR.getMessage() + "\","
					+ "\"details\":{\"refreshToken\":\"Refresh Token은 필수입니다.\"}}}", JsonCompareMode.STRICT));
		}
	}

	private ResultActions post(String path, String json) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static tools.jackson.databind.JsonNode data(ResultActions result) throws Exception {
		return JsonMapper.builder().build().readTree(result.andReturn().getResponse().getContentAsString()).get("data");
	}

	private static void assertFailure(ResultActions result, ErrorCode code) throws Exception {
		result.andExpect(status().is(code.getStatus().value()))
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"" + code.getCode()
				+ "\",\"message\":\"" + code.getMessage() + "\",\"details\":{}}}", JsonCompareMode.STRICT));
	}

	private static String body(String refreshToken) {
		return "{\"refreshToken\":\"" + refreshToken + "\"}";
	}

	private static String sha256(String raw) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}
}
