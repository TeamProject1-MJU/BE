package com.mju.linkro.common.config;

import com.mju.linkro.auth.config.AuthWebConfig;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.web.CurrentUser;
import com.mju.linkro.common.exception.GlobalExceptionHandler;
import com.mju.linkro.common.response.ApiResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs CorsConfig together with the real JWT interceptor so their ordering is covered. */
class CorsConfigTest {
    private static final String ALLOWED = "http://localhost:5173";
    private static final String DISALLOWED = "https://evil.example.com";
    private static final byte[] KEY = "test-only-secret-0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);

    private AnnotationConfigWebApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void allowedOriginPreflightReturnsCorsHeaders() throws Exception {
        mvc(ALLOWED + ",http://localhost:3000").perform(preflight("/api/v1/test/me", ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PUT,PATCH,DELETE,OPTIONS"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization, content-type"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    @Test
    void disallowedOriginPreflightIsRejected() throws Exception {
        mvc(ALLOWED).perform(preflight("/api/v1/test/me", DISALLOWED))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightWithUnlistedHeaderIsRejected() throws Exception {
        mvc(ALLOWED).perform(options("/api/v1/test/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-custom"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void allowedOriginWithValidTokenGetsCorsHeader() throws Exception {
        MockMvc mvc = mvc(ALLOWED);
        UUID userId = UUID.randomUUID();
        String token = context.getBean(JwtProvider.class).createAccessToken(userId);
        mvc.perform(get("/api/v1/test/me").header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(content().json("{\"success\":true,\"data\":\"" + userId + "\"}", JsonCompareMode.STRICT));
    }

    @Test
    void allowedOriginWithoutTokenGetsCorsHeaderOnAuthFailure() throws Exception {
        mvc(ALLOWED).perform(get("/api/v1/test/me").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"AUTH_REQUIRED\","
                        + "\"message\":\"인증이 필요합니다.\",\"details\":{}}}", JsonCompareMode.STRICT));
    }

    @Test
    void disallowedOriginActualRequestIsRejectedWithoutCorsHeader() throws Exception {
        mvc(ALLOWED).perform(get("/api/v1/test/me").header(HttpHeaders.ORIGIN, DISALLOWED))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void requestWithoutOriginKeepsExistingBehavior() throws Exception {
        mvc(ALLOWED).perform(get("/api/v1/test/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_MAX_AGE))
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"AUTH_REQUIRED\","
                        + "\"message\":\"인증이 필요합니다.\",\"details\":{}}}", JsonCompareMode.STRICT));
    }

    @Test
    void pathsOutsideApiHaveNoCorsMapping() throws Exception {
        MockMvc mvc = mvc(ALLOWED);
        mvc.perform(get("/swagger-ui/index.html").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(preflight("/swagger-ui/index.html", ALLOWED))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void originsAreTrimmedAndTrailingSlashesRemoved() {
        assertThat(CorsConfig.parseAllowedOrigins(" http://localhost:5173/ , https://linkro.vercel.app// ,,"))
                .containsExactly("http://localhost:5173", "https://linkro.vercel.app");
    }

    @Test
    void normalizedOriginMatchesBrowserOrigin() throws Exception {
        mvc(" " + ALLOWED + "/ ").perform(preflight("/api/v1/test/me", ALLOWED))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ",", " , / "})
    void emptyOriginsFailStartup(String value) {
        assertStartupFails(value, "must list at least one origin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "http://localhost:5173,*", "https://*.vercel.app"})
    void wildcardOriginsFailStartup(String value) {
        assertStartupFails(value, "must not contain \"*\"");
    }

    private void assertStartupFails(String value, String message) {
        assertThatThrownBy(() -> mvc(value))
                .isInstanceOf(BeanCreationException.class)
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CORS_ALLOWED_ORIGINS")
                .hasMessageContaining(message);
    }

    private static MockHttpServletRequestBuilder preflight(String path, String origin) {
        return options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
    }

    private MockMvc mvc(String allowedOrigins) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("cors.allowed-origins", allowedOrigins)));
        context.register(TestConfig.class);
        context.refresh();
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import({CorsConfig.class, AuthWebConfig.class, GlobalExceptionHandler.class, TestController.class})
    static class TestConfig {
        @Bean
        JwtProvider jwtProvider() {
            return new JwtProvider(Base64.getEncoder().encodeToString(KEY), Duration.ofMinutes(15));
        }
    }

    @RestController
    static class TestController {
        @GetMapping("/api/v1/test/me")
        ApiResponse<UUID> me(@CurrentUser UUID userId) { return ApiResponse.success(userId); }

        @GetMapping("/swagger-ui/index.html")
        ApiResponse<List<String>> swagger() { return ApiResponse.success(List.of("docs")); }
    }
}
