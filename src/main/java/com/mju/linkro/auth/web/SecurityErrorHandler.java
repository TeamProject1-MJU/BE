package com.mju.linkro.auth.web;

import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
		throws IOException {
		write(response, ErrorCode.AUTH_REQUIRED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
		throws IOException {
		write(response, ErrorCode.ACCESS_DENIED);
	}

	public void write(HttpServletResponse response, ErrorCode code) throws IOException {
		if (response.isCommitted()) { return; }
		response.setStatus(code.getStatus().value());
		response.setContentType("application/json");
		response.getOutputStream().write(mapper.writeValueAsBytes(
			ApiResponse.failure(code.getCode(), code.getMessage(), Map.of())));
	}
}
