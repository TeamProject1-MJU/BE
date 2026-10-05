package com.mju.linkro.auth;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.auth.controller.AuthController;
import com.mju.linkro.common.entity.BaseUuidTimeEntity;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class AuthServiceTest {
	@Autowired AuthService authService;
	@Autowired UserRepository users;
	@MockitoBean KakaoClient kakaoClient;
	@Value("${auth.jwt.secret}") String secret;
	@Value("${auth.jwt.access-token-ttl}") java.time.Duration ttl;

	@BeforeEach
	void clean() { users.deleteAll(); }

	@Test
	void newUserHasUuidV7AndSignedAccessToken() {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		var result = authService.login("test-token");
		assertThat(result.isNewUser()).isTrue();
		assertThat(result.user().id().version()).isEqualTo(7);
		assertThat(users.count()).isEqualTo(1);
		var user = users.findById(result.user().id()).orElseThrow();
		assertThat(user).isInstanceOf(BaseUuidTimeEntity.class);
		assertThat(user.isNew()).isFalse();
		assertThat(user.getCreatedAt()).isNotNull();
		assertThat(user.getUpdatedAt()).isNotNull();
		var jwt = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)))
			.build().parseSignedClaims(result.accessToken());
		assertThat(jwt.getHeader().getAlgorithm()).isEqualTo("HS256");
		var claims = jwt.getPayload();
		assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
		assertThat(claims.getIssuedAt()).isNotNull();
		assertThat(claims.getExpiration().toInstant()).isAfter(Instant.now());
		assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(ttl.toMillis());
	}

	@Test
	void newUserHttpResponseUsesExactCommonSuccessEnvelope() throws Exception {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
			.standaloneSetup(new AuthController(authService))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
		var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/v1/auth/kakao").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"kakaoAccessToken\":\"test-token\"}"))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
			.andReturn().getResponse().getContentAsString();
		var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response);
		String token = body.get("data").get("accessToken").asString();
		var user = users.findByKakaoId("123").orElseThrow();
		org.springframework.test.json.JsonAssert.comparator(org.springframework.test.json.JsonCompareMode.STRICT)
			.assertIsMatch("{\"success\":true,\"data\":{\"accessToken\":\"" + token
				+ "\",\"user\":{\"id\":\"" + user.getId() + "\",\"nickname\":\"인준\"},\"isNewUser\":true}}", response);
		assertThat(user.getId().version()).isEqualTo(7);
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void existingUserKeepsNickname() {
		var user = users.saveAndFlush(new User("123", "LinkRo 닉네임"));
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "카카오"));
		var result = authService.login("test-token");
		assertThat(result.isNewUser()).isFalse();
		assertThat(result.user().id()).isEqualTo(user.getId());
		assertThat(result.user().nickname()).isEqualTo("LinkRo 닉네임");
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void deletedUserIsRestoredWithSameIdAndNickname() {
		var user = new User("123", "기존 닉네임");
		user.softDelete();
		users.saveAndFlush(user);
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "카카오"));
		var result = authService.login("test-token");
		assertThat(result.isNewUser()).isFalse();
		assertThat(result.user().id()).isEqualTo(user.getId());
		assertThat(result.user().nickname()).isEqualTo("기존 닉네임");
		assertThat(users.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void longNicknameIsLimitedToTwentyCodePoints() {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "가".repeat(21)));
		String nickname = authService.login("test-token").user().nickname();
		assertThat(nickname).isEqualTo("가".repeat(20));
		assertThat(nickname.codePointCount(0, nickname.length())).isEqualTo(20);
	}

	@Test
	void concurrentFirstLoginsCreateOneUser() throws Exception {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "인준"));
		var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
		var start = new java.util.concurrent.CountDownLatch(1);
		try {
			java.util.concurrent.Callable<com.mju.linkro.auth.dto.KakaoLoginResponse> login = () -> {
				start.await();
				return authService.login("test-token");
			};
			var first = executor.submit(login);
			var second = executor.submit(login);
			start.countDown();
			var one = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
			var two = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(one.user().id()).isEqualTo(two.user().id());
			assertThat(one.isNewUser()).isNotEqualTo(two.isNewUser());
			assertThat(users.count()).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void missingNicknameGetsFallback() {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", null));
		assertThat(authService.login("test-token").user().nickname()).matches("사용자[0-9]{4}");
	}

	@Test
	void blankNicknameGetsFallback() {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "  "));
		assertThat(authService.login("test-token").user().nickname()).matches("사용자[0-9]{4}");
	}
}
