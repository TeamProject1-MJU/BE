package com.mju.linkro.auth.web;

import com.mju.linkro.auth.config.AuthWebConfig;
import com.mju.linkro.auth.config.SecurityConfig;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.config.CorsConfig;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import com.mju.linkro.common.response.ApiResponse;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs the real SecurityFilterChain and resolver registration against test-only controllers. */
class JwtAuthenticationFilterTest {
	private static final byte[] KEY = "test-only-secret-0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

	private AnnotationConfigWebApplicationContext context;
	private JwtProvider jwtProvider;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
			"test", Map.of("cors.allowed-origins", "http://localhost:3000")));
		context.register(TestConfig.class);
		context.refresh();
		jwtProvider = context.getBean(JwtProvider.class);
		mvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity()).build();
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void missingHeaderReturnsAuthRequired() throws Exception {
		assertFailure(mvc.perform(get("/api/v1/test/me")), "AUTH_REQUIRED", "인증이 필요합니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Bearer", "Bearer ", "Bearer    ", "Basic dXNlcjpwYXNz", "Token abc"})
	void nonBearerOrEmptyTokenReturnsAuthRequired(String header) throws Exception {
		assertFailure(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, header)),
			"AUTH_REQUIRED", "인증이 필요합니다.");
	}

	@Test
	void expiredTokenReturnsTokenExpired() throws Exception {
		String token = Jwts.builder().subject(UUID.randomUUID().toString())
			.expiration(Date.from(Instant.now().minusSeconds(60))).signWith(Keys.hmacShaKeyFor(KEY), Jwts.SIG.HS256).compact();
		assertFailure(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
			"TOKEN_EXPIRED", "토큰이 만료되었습니다.");
	}

	@Test
	void tamperedTokenReturnsTokenInvalid() throws Exception {
		String token = jwtProvider.createAccessToken(UUID.randomUUID());
		String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");
		assertFailure(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered)),
			"TOKEN_INVALID", "토큰이 올바르지 않습니다.");
	}

	@Test
	void validTokenInjectsSubjectAsCurrentUser() throws Exception {
		UUID userId = UUID.randomUUID();
		mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(userId)))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":{\"userId\":\"" + userId + "\"}}", JsonCompareMode.STRICT));
	}

	@Test
	void bearerSchemeIsCaseInsensitive() throws Exception {
		mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "bearer " + jwtProvider.createAccessToken(UUID.randomUUID())))
			.andExpect(status().isOk());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
	void publicAuthPostsPassWithoutHeader(String path) throws Exception {
		mvc.perform(post(path)).andExpect(status().isOk())
			.andExpect(content().json("{\"success\":true,\"data\":\"public\"}", JsonCompareMode.STRICT));
	}

	@Test
	void publicAuthPathRequiresTokenForOtherMethods() throws Exception {
		assertFailure(mvc.perform(get("/api/v1/auth/kakao")), "AUTH_REQUIRED", "인증이 필요합니다.");
	}

	@Test
	void publicPostPathsAreExactlyTheAuthEndpoints() {
		assertThat(JwtAuthenticationFilter.PUBLIC_POST_PATHS)
			.containsExactlyInAnyOrder("/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/users", "/api/v1/rooms", "/api/v1/rooms/join"})
	void protectedPostsWithoutHeaderReturnAuthRequired(String path) throws Exception {
		assertFailure(mvc.perform(post(path)), "AUTH_REQUIRED", "인증이 필요합니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/auth/kakao/", "/api/v1/auth/kakao/extra", "/api/v1/auth/kakaox"})
	void publicPathMatchIsExact(String path) throws Exception {
		mvc.perform(post(path)).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config"})
	void swaggerPathsPassWithoutHeader(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isOk());
	}

	@Test
	void corsPreflightPassesWithoutHeader() throws Exception {
		var result = mvc.perform(options("/api/v1/test/me")
				.header(HttpHeaders.ORIGIN, "http://localhost:3000")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
			.andReturn();
		assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
		assertThat(result.getResolvedException()).isNull();
	}

	@Test
	void currentUserWithoutAuthenticationReturnsAuthRequired() throws Exception {
		// /api/v1/auth/logout skips the JWT filter, so no userId is stored for the resolver.
		assertFailure(mvc.perform(post("/api/v1/auth/logout").param("me", "true")), "AUTH_REQUIRED", "인증이 필요합니다.");
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
	void publicPostsIgnoreExpiredAndMalformedAccessTokens(String path) throws Exception {
		String expired = Jwts.builder().subject(UUID.randomUUID().toString())
			.expiration(Date.from(Instant.now().minusSeconds(60)))
			.signWith(Keys.hmacShaKeyFor(KEY), Jwts.SIG.HS256).compact();
		for (String token : new String[] {expired, "garbage"}) {
			mvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
		}
		Mockito.verifyNoInteractions(context.getBean(UserRepository.class));
	}

	@Test
	void missingAndDeletedUsersAreRejectedBeforeController() throws Exception {
		var users = context.getBean(UserRepository.class);
		UUID id = UUID.randomUUID();
		Mockito.when(users.findById(id)).thenReturn(Optional.empty());
		assertFailure(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + jwtProvider.createAccessToken(id))), "TOKEN_INVALID", ErrorCode.TOKEN_INVALID.getMessage());
		var deleted = new User("456", "tester");
		deleted.softDelete();
		Mockito.when(users.findById(id)).thenReturn(Optional.of(deleted));
		assertFailure(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + jwtProvider.createAccessToken(id))), "TOKEN_INVALID", ErrorCode.TOKEN_INVALID.getMessage());
	}

	@Test
	void authenticationRunsOnceAndDoesNotLeakToNextRequestOrCreateSession() throws Exception {
		UUID id = UUID.randomUUID();
		var result = mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + jwtProvider.createAccessToken(id))).andExpect(status().isOk()).andReturn();
		Mockito.verify(context.getBean(UserRepository.class)).findById(id);
		assertThat(result.getRequest().getSession(false)).isNull();
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertFailure(mvc.perform(get("/api/v1/test/me")), "AUTH_REQUIRED", ErrorCode.AUTH_REQUIRED.getMessage());
		assertThat(context.getBeansOfType(JwtAuthenticationFilter.class)).isEmpty();
	}

	@Test
	void downstreamBusinessFailureIsNotOverwrittenOrReauthenticated() throws Exception {
		UUID id = UUID.randomUUID();
		mvc.perform(get("/api/v1/test/failure").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + jwtProvider.createAccessToken(id)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_FULL"));
		Mockito.verify(context.getBean(UserRepository.class)).findById(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/auth/%6bakao", "/api/v1/auth/kakao%2Fextra"})
	void encodedPublicLookalikesAreNeverPublic(String path) throws Exception {
		int status = mvc.perform(post(java.net.URI.create(path))).andReturn().getResponse().getStatus();
		assertThat(status).isIn(400, 401);
	}

	@Test
	void databaseFailureReturnsSafeJsonWithCorsAndDoesNotLeakIntoNextRequest() throws Exception {
		UUID id = UUID.randomUUID();
		Mockito.when(context.getBean(UserRepository.class).findById(id))
			.thenThrow(new org.springframework.dao.DataAccessResourceFailureException("jdbc:secret password=hidden"));
		var result = mvc.perform(get("/api/v1/test/me")
			.header(HttpHeaders.ORIGIN, "http://localhost:3000")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(id)))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\""
				+ ErrorCode.INTERNAL_ERROR.getMessage() + "\",\"details\":{}}}", JsonCompareMode.STRICT))
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
			.andReturn();
		assertThat(result.getHandler()).isNull();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("jdbc", "password", "DataAccess", "stack");
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		assertFailure(mvc.perform(get("/api/v1/test/me")), "AUTH_REQUIRED", ErrorCode.AUTH_REQUIRED.getMessage());
		UUID nextId = UUID.randomUUID();
		mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION,
			"Bearer " + jwtProvider.createAccessToken(nextId)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.userId").value(nextId.toString()));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/", "/unknown", "/ws", "/ws/connect", "/swagger-ui-extra", "/v3/api-docsx", "/error"})
	void unlistedExternalPathsRequireAuthentication(String path) throws Exception {
		assertFailure(mvc.perform(get(path)), "AUTH_REQUIRED", ErrorCode.AUTH_REQUIRED.getMessage());
	}

	@Test
	void authenticatedRequestToUnlistedPathIsDeniedWithCommonJson() throws Exception {
		mvc.perform(get("/unknown").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
			.authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(UUID.randomUUID(), null, java.util.List.of()))))
			.andExpect(status().isForbidden())
			.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"ACCESS_DENIED\",\"message\":\""
				+ ErrorCode.ACCESS_DENIED.getMessage() + "\",\"details\":{}}}", JsonCompareMode.STRICT));
	}

	@Test
	void bearerHeaderOutsideApiDoesNotAuthenticateAndUnknownAuthenticatedApiRemains404() throws Exception {
		String token = jwtProvider.createAccessToken(UUID.randomUUID());
		assertFailure(mvc.perform(get("/unknown").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
			"AUTH_REQUIRED", ErrorCode.AUTH_REQUIRED.getMessage());
		Mockito.verifyNoInteractions(context.getBean(UserRepository.class));
		mvc.perform(get("/api/v1/unknown").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout"})
	void otherPublicEndpointMethodsAreProtected(String path) throws Exception {
		for (String method : new String[] {"GET", "PUT", "PATCH", "DELETE", "OPTIONS"}) {
			assertFailure(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.request(org.springframework.http.HttpMethod.valueOf(method), path)), "AUTH_REQUIRED", ErrorCode.AUTH_REQUIRED.getMessage());
		}
	}

	@Test
	void errorDispatchPassesSecurityWithoutAuthentication() throws Exception {
		mvc.perform(get("/api/v1/test/failure").with(request -> { request.setDispatcherType(jakarta.servlet.DispatcherType.ERROR); return request; })
			.requestAttr("jakarta.servlet.error.request_uri", "/api/v1/test/failure"))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROOM_FULL"));
		Mockito.verifyNoInteractions(context.getBean(UserRepository.class));
	}

	private static void assertFailure(ResultActions result, String code, String message) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
				+ "\",\"details\":{}}}", JsonCompareMode.STRICT));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	@Import({SecurityConfig.class, CorsConfig.class, AuthWebConfig.class, GlobalExceptionHandler.class, TestController.class})
	static class TestConfig {
		@Bean
		UserRepository users() {
			var users = Mockito.mock(UserRepository.class);
			Mockito.when(users.findById(ArgumentMatchers.any(UUID.class)))
				.thenReturn(Optional.of(new User("123", "tester")));
			return users;
		}

		@Bean
		JwtProvider jwtProvider() {
			return new JwtProvider(Base64.getEncoder().encodeToString(KEY), Duration.ofMinutes(15));
		}
	}

	@RestController
	static class TestController {
		@GetMapping("/api/v1/test/failure")
		ApiResponse<Void> failure() {
			throw new BusinessException(ErrorCode.ROOM_FULL);
		}

		@GetMapping("/api/v1/test/me")
		ApiResponse<Map<String, UUID>> me(@CurrentUser UUID userId) { return ApiResponse.success(Map.of("userId", userId)); }

		@PostMapping({"/api/v1/auth/kakao", "/api/v1/auth/refresh"})
		ApiResponse<String> publicAuth() { return ApiResponse.success("public"); }

		// Only POST is public; a GET on the same path must still require a token.
		@GetMapping("/api/v1/auth/kakao")
		ApiResponse<String> kakaoGet() { return ApiResponse.success("protected"); }

		@PostMapping(value = "/api/v1/auth/logout", params = "!me")
		ApiResponse<String> logout() { return ApiResponse.success("public"); }

		@PostMapping(value = "/api/v1/auth/logout", params = "me")
		ApiResponse<UUID> logoutWithCurrentUser(@CurrentUser UUID userId) { return ApiResponse.success(userId); }

		@PostMapping({"/api/v1/users", "/api/v1/rooms", "/api/v1/rooms/join"})
		ApiResponse<String> protectedPost() { return ApiResponse.success("protected"); }

		@PostMapping({"/api/v1/auth/kakao/", "/api/v1/auth/kakao/extra", "/api/v1/auth/kakaox"})
		ApiResponse<String> lookalike() { return ApiResponse.success("protected"); }

		@GetMapping({"/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs/swagger-config"})
		ApiResponse<String> swagger() { return ApiResponse.success("docs"); }
	}
}
