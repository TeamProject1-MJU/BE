package com.mju.linkro.common.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Refresh tokens travel in the body, so credentials (cookies) are never allowed. */
@Configuration(proxyBeanMethods = false)
public class CorsConfig implements WebMvcConfigurer {
    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins}") String allowedOrigins) {
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins);
    }

    /** Trims each comma-separated origin and drops trailing slashes so it matches the browser's Origin header. */
    static List<String> parseAllowedOrigins(String raw) {
        List<String> origins = raw == null ? List.of() : Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(origin -> origin.replaceAll("/+$", ""))
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList();
        if (origins.isEmpty()) {
            throw new IllegalArgumentException(
                    "CORS_ALLOWED_ORIGINS (cors.allowed-origins) must list at least one origin, e.g. https://linkro.vercel.app");
        }
        if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
            throw new IllegalArgumentException(
                    "CORS_ALLOWED_ORIGINS (cors.allowed-origins) must not contain \"*\"; list each frontend origin explicitly");
        }
        return origins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
