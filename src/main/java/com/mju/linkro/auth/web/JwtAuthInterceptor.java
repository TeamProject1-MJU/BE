package com.mju.linkro.auth.web;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/** Identifies the user from the access token only; room permissions are checked by each API. */
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {
	public static final String USER_ID_ATTRIBUTE = JwtAuthInterceptor.class.getName() + ".userId";
	private static final String BEARER_PREFIX = "Bearer ";
	// Package-private so tests can pin the exact list.
	static final Set<String> PUBLIC_POST_PATHS = Set.of(
		"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout");

	private final JwtProvider jwtProvider;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (CorsUtils.isPreFlightRequest(request) || isPublic(request)) {
			return true;
		}
		request.setAttribute(USER_ID_ATTRIBUTE, jwtProvider.parseUserId(extractToken(request)));
		return true;
	}

	// Exact raw-URI match: encoded or suffixed variants fall back to requiring a token.
	private static boolean isPublic(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return HttpMethod.POST.matches(request.getMethod()) && PUBLIC_POST_PATHS.contains(path);
	}

	private static String extractToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			throw new BusinessException(ErrorCode.AUTH_REQUIRED);
		}
		String token = header.substring(BEARER_PREFIX.length());
		if (token.isBlank()) {
			throw new BusinessException(ErrorCode.AUTH_REQUIRED);
		}
		return token;
	}
}
