package com.fachat.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;

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
        "RATE_LIMIT_ENABLED=true",
        "RATE_LIMIT_GLOBAL_PER_MINUTE=1",
        "RATE_LIMIT_CHAT_PER_MINUTE=1",
        "RATE_LIMIT_CHAT_USER_PER_MINUTE=1",
        "RATE_LIMIT_BLOCK_SECONDS=60",
        "MODEL_CATALOG_DYNAMIC_ENABLED=false",
        "DATABASE_URL=jdbc:h2:mem:rate_limit_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop"
    }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RateLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void shouldReturnTooManyRequestsAfterLimit() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/api/config"))
            .GET()
            .build();

        HttpResponse<String> first = client.send(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(second.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(second.headers().firstValue("Retry-After")).isPresent();
    }
}
