package com.fachat.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AgentProperties properties;

    public WebConfig(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> allowedOrigins = properties.getCorsAllowedOrigins();

        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders(
                "Content-Type",
                "X-Session-User-Id",
                "X-Session-User-Name",
                "X-Session-User-Email",
                "X-Internal-Api-Key",
                "X-Request-Id"
            )
            .exposedHeaders(
                "X-Request-Id",
                "Retry-After",
                "X-RateLimit-Limit",
                "X-RateLimit-Remaining"
            )
            .allowCredentials(false)
            .maxAge(3600);
    }
}
