package com.mju.linkro.user;

import com.mju.linkro.auth.client.KakaoClient;
import com.mju.linkro.auth.domain.RefreshToken;
import com.mju.linkro.auth.dto.KakaoUserInfo;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full context, so the real JWT filter and @CurrentUser resolver run in front of the controller. */
@SpringBootTest
@ActiveProfiles("test")
class UserControllerTest {
	private static final String ME = "/api/v1/users/me";

	@Autowired WebApplicationContext context;
	@Autowired UserRepository users;
	@Autowired RefreshTokenRepository refreshTokens;
	@Autowired JwtProvider jwtProvider;
	@MockitoBean KakaoClient kakaoClient;
	MockMvc mvc;
	User user;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).build();
		refreshTokens.deleteAll();
		users.deleteAll();
		user = users.saveAndFlush(new User("123", "인준"));
	}

	@Test
	void allThreeEndpointsRequireAuthorizationHeader() throws Exception {
		assertFailure(mvc.perform(get(ME)), ErrorCode.AUTH_REQUIRED);
		assertFailure(mvc.perform(json(patch(ME), "{\"nickname\":\"장선호\"}")), ErrorCode.AUTH_REQUIRED);
		assertFailure(mvc.perform(delete(ME)), ErrorCode.AUTH_REQUIRED);
		assertThat(users.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
	}

	@Test
	void getMeReturnsSpecFieldsWithSeoulOffset() throws Exception {
		String createdAt = user.getCreatedAt().truncatedTo(ChronoUnit.SECONDS).atZone(ZoneId.of("Asia/Seoul"))
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		var result = mvc.perform(auth(get(ME))).andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":{\"id\":\"" + user.getId()
				+ "\",\"nickname\":\"인준\",\"createdAt\":\"" + createdAt + "\"}}", JsonCompareMode.STRICT));
		assertThat(createdAt).endsWith("+09:00");
		// Field order follows API spec 4.4.
		assertThat(result.andReturn().getResponse().getContentAsString())
			.containsSubsequence("\"id\"", "\"nickname\"", "\"createdAt\"");
	}

	@Test
	void patchMeReturnsIdAndStrippedNickname() throws Exception {
		mvc.perform(json(auth(patch(ME)), "{\"nickname\":\"  장선호  \"}"))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":{\"id\":\"" + user.getId()
				+ "\",\"nickname\":\"장선호\"}}", JsonCompareMode.STRICT));
		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("장선호");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"nickname\":\"가\"}", "{\"nickname\":\"   \"}",
		"{\"nickname\":\"가가가가가가가가가가가가가가가가가가가가가\"}", "{\"nickname\":null}", "{}"})
	void invalidNicknameReturnsValidationError(String body) throws Exception {
		mvc.perform(json(auth(patch(ME)), body))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\""
				+ ErrorCode.VALIDATION_ERROR.getMessage() + "\",\"details\":{\"nickname\":"
				+ "\"닉네임은 앞뒤 공백을 제외하고 2~20자여야 합니다.\"}}}", JsonCompareMode.STRICT));
	}

	@Test
	void deleteMeReturnsNullDataAndWithdrawnUserGetsTokenInvalid() throws Exception {
		mvc.perform(auth(delete(ME)))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":null}", JsonCompareMode.STRICT));

		// The access token itself is still valid, but the account is gone.
		assertFailure(mvc.perform(auth(get(ME))), ErrorCode.TOKEN_INVALID);
		assertFailure(mvc.perform(json(auth(patch(ME)), "{\"nickname\":\"장선호\"}")), ErrorCode.TOKEN_INVALID);
		assertFailure(mvc.perform(auth(delete(ME))), ErrorCode.TOKEN_INVALID);
	}

	@Test
	void loginAfterWithdrawalRestoresSameAccountWithFreshTokens() throws Exception {
		when(kakaoClient.getUserInfo("test-token")).thenReturn(new KakaoUserInfo("123", "카카오"));
		String oldRefresh = data(mvc.perform(json(post("/api/v1/auth/kakao"), "{\"kakaoAccessToken\":\"test-token\"}")))
			.get("refreshToken").asString();

		mvc.perform(auth(delete(ME))).andExpect(status().isOk());
		assertThat(refreshTokens.findAll()).allSatisfy(token -> assertThat(token.getRevokedAt()).isNotNull());
		assertFailure(mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + oldRefresh + "\"}")),
			ErrorCode.TOKEN_INVALID);

		var login = data(mvc.perform(json(post("/api/v1/auth/kakao"), "{\"kakaoAccessToken\":\"test-token\"}"))
			.andExpect(status().isOk()));
		assertThat(login.get("user").get("id").asString()).isEqualTo(user.getId().toString());
		assertThat(login.get("user").get("nickname").asString()).isEqualTo("인준");
		assertThat(login.get("isNewUser").asBoolean()).isFalse();
		assertThat(users.findById(user.getId()).orElseThrow().getDeletedAt()).isNull();
		assertThat(users.count()).isEqualTo(1);
		mvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + login.get("accessToken").asString()))
			.andExpect(status().isOk());
		mvc.perform(json(post("/api/v1/auth/refresh"), "{\"refreshToken\":\"" + login.get("refreshToken").asString() + "\"}"))
			.andExpect(status().isOk());
		assertThat(refreshTokens.findAll()).extracting(RefreshToken::getUserId).containsOnly(user.getId());
	}

	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(user.getId()));
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
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
}
