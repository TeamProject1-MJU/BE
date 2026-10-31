package com.mju.linkro.auth.web;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SecurityErrorHandlerTest {
	@Test
	void entryPointAndDeniedHandlerUseJsonWithoutRedirectAndPreserveCors() throws Exception {
		var handler = new SecurityErrorHandler();
		var request = new MockHttpServletRequest();
		var unauthorized = new MockHttpServletResponse();
		unauthorized.setHeader("Access-Control-Allow-Origin", "http://localhost:3000");
		handler.commence(request, unauthorized, new InsufficientAuthenticationException("internal detail"));
		assertFailure(unauthorized, ErrorCode.AUTH_REQUIRED);
		assertThat(unauthorized.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
		var denied = new MockHttpServletResponse();
		handler.handle(request, denied, new AccessDeniedException("internal detail"));
		assertFailure(denied, ErrorCode.ACCESS_DENIED);
	}

	@Test
	void errorDispatchDoesNotAuthenticateAgainOrReplaceExistingResponse() throws Exception {
		var jwt = mock(JwtProvider.class);
		var users = mock(UserRepository.class);
		var filter = new JwtAuthenticationFilter(jwt, users, new SecurityErrorHandler());
		var request = new MockHttpServletRequest("GET", "/api/v1/test/failure");
		request.setDispatcherType(DispatcherType.ERROR);
		request.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/test/failure");
		request.addHeader("Authorization", "Bearer " + UUID.randomUUID());
		var response = new MockHttpServletResponse();
		response.setStatus(500);
		response.getWriter().write("original error");
		filter.doFilter(request, response, (req, res) -> {});
		verifyNoInteractions(jwt, users);
		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).isEqualTo("original error");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
	}

	@Test
	void repositoryFailureClearsExistingAuthenticationAndStopsChainWithSafeLog() throws Exception {
		var jwt = mock(JwtProvider.class);
		var users = mock(UserRepository.class);
		UUID id = UUID.randomUUID();
		when(jwt.parseUserId("secret-token")).thenReturn(id);
		when(users.findById(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
			"jdbc:private password=secret-token"));
		var filter = new JwtAuthenticationFilter(jwt, users, new SecurityErrorHandler());
		var request = new MockHttpServletRequest("GET", "/api/v1/test/me");
		request.addHeader("Authorization", "Bearer secret-token");
		var response = new MockHttpServletResponse();
		var chain = mock(jakarta.servlet.FilterChain.class);
		var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter.class);
		var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
		logs.start();
		logger.addAppender(logs);
		SecurityContextHolder.getContext().setAuthentication(
			new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(id, null, java.util.List.of()));
		try {
			filter.doFilter(request, response, chain);
			assertFailure(response, ErrorCode.INTERNAL_ERROR);
			assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
			verifyNoInteractions(chain);
			assertThat(logs.list).hasSize(1);
			assertThat(logs.list.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
			assertThat(logs.list.get(0).getFormattedMessage()).contains("DataAccessResourceFailureException", "SecurityErrorHandlerTest")
				.doesNotContain("jdbc:private", "password", "secret-token", "Authorization");
			assertThat(logs.list.get(0).getThrowableProxy()).isNull();
		} finally {
			logger.detachAppender(logs);
			logs.stop();
			SecurityContextHolder.clearContext();
		}
	}

	private static void assertFailure(MockHttpServletResponse response, ErrorCode code) throws Exception {
		assertThat(response.getStatus()).isEqualTo(code.getStatus().value());
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getRedirectedUrl()).isNull();
		assertThat(response.getHeader("WWW-Authenticate")).isNull();
		var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(response.getContentAsByteArray());
		assertThat(json.get("success").asBoolean()).isFalse();
		assertThat(json.get("error").get("code").asString()).isEqualTo(code.getCode());
		assertThat(json.get("error").get("message").asString()).isEqualTo(code.getMessage());
		assertThat(json.get("error").get("details").isEmpty()).isTrue();
		assertThat(response.getContentAsString()).doesNotContain("internal detail");
	}
}
