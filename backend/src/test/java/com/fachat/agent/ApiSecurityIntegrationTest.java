package com.fachat.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = AgentApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "ENVIRONMENT=local",
        "CORS_ALLOWED_ORIGINS=http://allowed.local",
        "INTERNAL_API_SECRET=test-internal-secret",
        "RATE_LIMIT_ENABLED=false",
        "MODEL_CATALOG_DYNAMIC_ENABLED=false",
        "DATABASE_URL=jdbc:h2:mem:api_security_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop"
    }
)
class ApiSecurityIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void healthShouldBePublic() throws Exception {
        HttpResponse<String> response = send("GET", "/health", null, null);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
    }

    @Test
    void restrictedActuatorEndpointShouldBeHidden() throws Exception {
        HttpResponse<String> response = send("GET", "/actuator/env", null, null);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    void corsShouldRejectUnknownOrigin() throws Exception {
        HttpResponse<String> response = send("GET", "/api/config", "Origin", "http://evil.local");
        assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void sensitiveEndpointShouldRejectMissingInternalKey() throws Exception {
        HttpResponse<String> response = send("GET", "/api/conversations", null, null);
        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    void sensitiveEndpointShouldRejectInvalidInternalKey() throws Exception {
        HttpResponse<String> response = send(
            "GET",
            "/api/conversations",
            "X-Internal-Api-Key",
            "wrong-secret"
        );
        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.body()).contains("Unauthorized");
    }

    @Test
    void sensitiveEndpointShouldAcceptValidInternalKeyAndContinueAuthFlow() throws Exception {
        HttpResponse<String> response = send(
            "GET",
            "/api/conversations",
            "X-Internal-Api-Key",
            "test-internal-secret"
        );
        assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.body()).contains("Login required");
    }

    private HttpResponse<String> send(
        String method,
        String path,
        String headerName,
        String headerValue
    ) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (headerName != null && headerValue != null) {
            builder.header(headerName, headerValue);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
