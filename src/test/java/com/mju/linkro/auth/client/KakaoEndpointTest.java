package com.mju.linkro.auth.client;

import com.mju.linkro.auth.controller.AuthController;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.service.AuthService;
import com.mju.linkro.auth.service.UserLoginService;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.test.json.JsonCompareMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KakaoEndpointTest {
	MockRestServiceServer server;
	KakaoClient client;
	UserLoginService users;
	JwtProvider jwt;
	MockMvc mvc;

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new KakaoClient(builder.build(), 1234);
		users = mock(UserLoginService.class);
		jwt = mock(JwtProvider.class);
		mvc = MockMvcBuilders.standaloneSetup(new AuthController(new AuthService(client, users, jwt)))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@ParameterizedTest
	@EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
	void invalidTokenReturns401(HttpStatus upstreamStatus) throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
			.andRespond(withStatus(upstreamStatus));
		request().andExpect(status().isUnauthorized()).andExpect(content().json(
			"{\"success\":false,\"error\":{\"code\":\"KAKAO_AUTH_FAILED\",\"message\":\"카카오 인증에 실패했습니다.\",\"details\":{}}}",
			JsonCompareMode.STRICT));
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void externalFailureReturns502() throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withServerError());
		assertExternalFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void readsAccountProfileAndIgnoresDeprecatedProperties() {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withSuccess(
			"{\"id\":123,\"properties\":{\"nickname\":\"old\"},\"kakao_account\":{\"profile\":{\"nickname\":\"correct\"}}}", MediaType.APPLICATION_JSON));
		var info = client.getUserInfo("invalid");
		assertThat(info.kakaoId()).isEqualTo("123");
		assertThat(info.nickname()).isEqualTo("correct");
		server.verify();
	}

	@Test
	void networkFailureReturns502() throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withException(new java.io.IOException("private upstream body Authorization Bearer invalid")));
		var result = assertExternalFailure(request()).andReturn();
		var exception = (com.mju.linkro.common.exception.BusinessException) result.getResolvedException();
		assertThat(exception.getCause()).isNotNull();
		assertThat(exception.getCause().getMessage()).doesNotContain("private upstream body", "Bearer", "invalid");
		assertThat(exception.getCause().getCause()).isNull();
		var stack = new java.io.StringWriter();
		exception.printStackTrace(new java.io.PrintWriter(stack));
		assertThat(stack.toString()).doesNotContain("private upstream body", "Authorization", "Bearer invalid");
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("private upstream body", "Authorization", "Bearer", "invalid", "RestClientException", "stackTrace");
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void unrecoverableInsertConflictUsesCommonInternalError() throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON));
		when(users.login(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("private DB details"));
		when(users.loginExisting("123")).thenReturn(null);
		request().andExpect(status().isInternalServerError()).andExpect(content().json(
			"{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\",\"details\":{}}}",
			JsonCompareMode.STRICT));
		server.verify();
	}

	private org.springframework.test.web.servlet.ResultActions assertExternalFailure(
		org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andExpect(status().isBadGateway()).andExpect(content().json(
			"{\"success\":false,\"error\":{\"code\":\"EXTERNAL_API_ERROR\",\"message\":\"외부 서비스 호출에 실패했습니다.\",\"details\":{}}}",
			JsonCompareMode.STRICT));
	}

	@Test
	void matchingAppAndUserIdsAllowLogin() throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess("{\"id\":123,\"kakao_account\":{\"profile\":{\"nickname\":\"name\"}}}", MediaType.APPLICATION_JSON));
		var user = new com.mju.linkro.user.domain.User("123", "name");
		when(users.login(any())).thenReturn(new UserLoginService.LoginResult(user, true));
		when(jwt.createAccessToken(user.getId())).thenReturn("test-jwt");
		request().andExpect(status().isOk()).andExpect(content().json(
			"{\"success\":true,\"data\":{\"accessToken\":\"test-jwt\",\"user\":{\"id\":\"" + user.getId()
				+ "\",\"nickname\":\"name\"},\"isNewUser\":true}}", JsonCompareMode.STRICT));
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"id\":123,\"expires_in\":60,\"app_id\":9999}",
		"{\"id\":123,\"expires_in\":0,\"app_id\":1234}",
		"{\"id\":123,\"app_id\":1234}",
		"{\"id\":0,\"expires_in\":60,\"app_id\":1234}"
	})
	void wrongAppOrInvalidTokenInfoFailsBeforeUserLookup(String body) throws Exception {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertAuthFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@ParameterizedTest
	@EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
	void tokenInfoAuthenticationFailureReturns401(HttpStatus upstream) throws Exception {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
			.andRespond(withStatus(upstream));
		assertAuthFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void tokenInfoServerFailureReturns502() throws Exception {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info")).andRespond(withServerError());
		assertExternalFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void tokenInfoNetworkFailureReturns502() throws Exception {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
			.andRespond(withException(new java.io.IOException("private token response")));
		assertExternalFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void mismatchedUserIdReturns401() throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess("{\"id\":456}", MediaType.APPLICATION_JSON));
		assertAuthFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{}", "{\"id\":0}", "{\"id\":-1}"})
	void emptyOrInvalidUserInfoReturns502(String body) throws Exception {
		expectValidToken();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertExternalFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"kakaoAccessToken\":\"\"}", "{\"kakaoAccessToken\":\"  \"}"})
	void missingOrBlankTokenUsesStrictValidationEnvelope(String body) throws Exception {
		mvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest()).andExpect(content().json(
				"{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\",\"message\":\"요청 값이 올바르지 않습니다.\","
					+ "\"details\":{\"kakaoAccessToken\":\"카카오 Access Token은 필수입니다.\"}}}", JsonCompareMode.STRICT));
		server.verify();
		verifyNoInteractions(users);
	}

	private void expectValidToken() {
		server.expect(requestTo("https://kapi.kakao.com/v1/user/access_token_info"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer invalid"))
			.andRespond(withSuccess("{\"id\":123,\"expires_in\":60,\"app_id\":1234}", MediaType.APPLICATION_JSON));
	}

	private org.springframework.test.web.servlet.ResultActions assertAuthFailure(
		org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andExpect(status().isUnauthorized()).andExpect(content().json(
			"{\"success\":false,\"error\":{\"code\":\"KAKAO_AUTH_FAILED\",\"message\":\"카카오 인증에 실패했습니다.\",\"details\":{}}}",
			JsonCompareMode.STRICT));
	}

	private org.springframework.test.web.servlet.ResultActions request() throws Exception {
		return mvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
			.content("{\"kakaoAccessToken\":\"invalid\"}"));
	}

}
