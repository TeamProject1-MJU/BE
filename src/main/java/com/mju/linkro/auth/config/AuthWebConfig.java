package com.mju.linkro.auth.config;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.web.CurrentUserArgumentResolver;
import com.mju.linkro.auth.web.JwtAuthInterceptor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AuthWebConfig implements WebMvcConfigurer {
	private final JwtProvider jwtProvider;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// Swagger is outside /api/v1/** already; listed so widening the pattern keeps it public.
		registry.addInterceptor(new JwtAuthInterceptor(jwtProvider))
			.addPathPatterns("/api/v1/**")
			.excludePathPatterns("/swagger-ui/**", "/v3/api-docs/**");
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(new CurrentUserArgumentResolver());
	}
}
