package com.mju.linkro.auth.config;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.web.JwtAuthenticationFilter;
import com.mju.linkro.auth.web.SecurityErrorHandler;
import com.mju.linkro.user.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider, UserRepository users,
		UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
		SecurityErrorHandler errors = new SecurityErrorHandler();
		return http
			.cors(cors -> cors.configurationSource(corsConfigurationSource))
			// Tokens travel in Authorization/body; no cookie authentication is used.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache(cache -> cache.disable())
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(JwtAuthenticationFilter::isPublic).permitAll()
				.requestMatchers("/api/v1/**").authenticated()
				.anyRequest().permitAll())
			// Constructed only here: never registered as a Servlet filter bean.
			.addFilterBefore(new JwtAuthenticationFilter(jwtProvider, users, errors), UsernamePasswordAuthenticationFilter.class)
			.build();
	}
}
