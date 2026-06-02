package com.fachat.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductionSafetyValidator {

    public ProductionSafetyValidator(
        AgentProperties properties,
        @Value("${spring.jpa.hibernate.ddl-auto:update}") String ddlAuto,
        @Value("${CORS_ALLOWED_ORIGINS:}") String corsAllowedOriginsRaw,
        @Value("${INTERNAL_API_SECRET:}") String internalApiSecretRaw,
        @Value("${DATABASE_URL:}") String databaseUrlRaw,
        @Value("${DATABASE_USERNAME:}") String databaseUsernameRaw,
        @Value("${DATABASE_PASSWORD:}") String databasePasswordRaw,
        @Value("${OPENROUTER_API_KEY:}") String openRouterApiKeyRaw
    ) {
        if (!properties.isProduction()) {
            return;
        }

        validateRequired("CORS_ALLOWED_ORIGINS", corsAllowedOriginsRaw);
        validateCors(properties.getCorsAllowedOrigins());
        validateRequired("INTERNAL_API_SECRET", internalApiSecretRaw);
        validateInternalSecret(properties.getInternalApiSecret());
        validateRequired("DATABASE_URL", databaseUrlRaw);
        validateRequired("DATABASE_USERNAME", databaseUsernameRaw);
        validateRequired("DATABASE_PASSWORD", databasePasswordRaw);
        validateRequired("OPENROUTER_API_KEY", openRouterApiKeyRaw);
        validateDdlAuto(ddlAuto);
    }

    private void validateRequired(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                "Production startup blocked: required environment variable '" + key + "' is missing."
            );
        }
    }

    private void validateCors(List<String> origins) {
        boolean invalid = origins == null
            || origins.isEmpty()
            || origins.stream().anyMatch(origin -> origin == null || origin.isBlank() || origin.contains("*"));

        if (invalid) {
            throw new IllegalStateException(
                "Production startup blocked: CORS_ALLOWED_ORIGINS must be explicit and cannot contain '*'."
            );
        }
    }

    private void validateInternalSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "Production startup blocked: INTERNAL_API_SECRET is required."
            );
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException(
                "Production startup blocked: INTERNAL_API_SECRET must be at least 32 characters long."
            );
        }
    }

    private void validateDdlAuto(String ddlAuto) {
        if (!"validate".equalsIgnoreCase(ddlAuto == null ? "" : ddlAuto.trim())) {
            throw new IllegalStateException(
                "Production startup blocked: spring.jpa.hibernate.ddl-auto must be 'validate' in production."
            );
        }
    }
}
