package com.mju.linkro.auth.web;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** Identifies the user from the access token only; room permissions are checked by each API. */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
	private static final String BEARER_PREFIX = "Bearer ";
	// Package-private so tests can pin the exact list.
	static final Set<String> PUBLIC_POST_PATHS = Set.of(
		"/api/v1/auth/kakao", "/api/v1/auth/refresh", "/api/v1/auth/logout");

	private final JwtProvider jwtProvider;

	private final UserRepository users;
	private final SecurityErrorHandler errors;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !(path.equals("/api/v1") || path.startsWith("/api/v1/")) || isPublic(request)
			|| CorsUtils.isPreFlightRequest(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		try {
			var userId = jwtProvider.parseUserId(extractToken(request));
			if (users.findById(userId).filter(user -> user.getDeletedAt() == null).isEmpty()) {
				throw new BusinessException(ErrorCode.TOKEN_INVALID);
			}
			var context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of()));
			SecurityContextHolder.setContext(context);
		} catch (BusinessException exception) {
			SecurityContextHolder.clearContext();
			errors.write(response, exception.getErrorCode());
			return;
		}
		// Do not rewrite downstream failures as JWT failures.
		chain.doFilter(request, response);
	}

	// Exact raw-URI match: encoded or suffixed variants fall back to requiring a token.
	public static boolean isPublic(HttpServletRequest request) {
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
