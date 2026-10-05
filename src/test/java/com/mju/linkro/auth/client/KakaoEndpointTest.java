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
	MockMvc mvc;

	@BeforeEach
	void setup() {
		var builder = RestClient.builder().baseUrl("https://kapi.kakao.com");
		server = MockRestServiceServer.bindTo(builder).build();
		client = new KakaoClient(builder.build());
		users = mock(UserLoginService.class);
		mvc = MockMvcBuilders.standaloneSetup(new AuthController(new AuthService(client, users, mock(JwtProvider.class))))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@ParameterizedTest
	@EnumSource(value = HttpStatus.class, names = {"UNAUTHORIZED", "FORBIDDEN"})
	void invalidTokenReturns401(HttpStatus upstreamStatus) throws Exception {
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
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withServerError());
		assertExternalFailure(request());
		server.verify();
		verifyNoInteractions(users);
	}

	@Test
	void readsAccountProfileAndIgnoresDeprecatedProperties() {
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me")).andRespond(withSuccess(
			"{\"id\":123,\"properties\":{\"nickname\":\"old\"},\"kakao_account\":{\"profile\":{\"nickname\":\"correct\"}}}", MediaType.APPLICATION_JSON));
		var info = client.getUserInfo("invalid");
		assertThat(info.kakaoId()).isEqualTo("123");
		assertThat(info.nickname()).isEqualTo("correct");
		server.verify();
	}

	@Test
	void networkFailureReturns502() throws Exception {
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

	private org.springframework.test.web.servlet.ResultActions request() throws Exception {
		return mvc.perform(post("/api/v1/auth/kakao").contentType(MediaType.APPLICATION_JSON)
			.content("{\"kakaoAccessToken\":\"invalid\"}"));
	}

}
