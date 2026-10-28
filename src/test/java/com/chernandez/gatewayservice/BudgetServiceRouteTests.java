package com.chernandez.gatewayservice;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Date;
import java.util.Map;

/**
 * The budget-service route: protected by the JWT filter like every non-open route, and answered by its own
 * fallback when the service is down.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "JWT_SECRET_KEY=" + BudgetServiceRouteTests.SECRET,
    "INTERNAL_API_KEY=test-internal-key"
})
class BudgetServiceRouteTests {

    static final String SECRET = "VGhpc0lzQVRlc3RLZXlGb3JHYXRld2F5U2VydmljZUFuZEl0SXNMb25nRW5vdWdo";

    private static final StubBackend BACKEND = new StubBackend();

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("BUDGET_SERVICE_URL", BACKEND::url);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        BACKEND.use(StubBackend.OK);
        client = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Test
    void requestsWithoutTokenNeverReachBudgetService() {
        client.get().uri("/api/projects").exchange()
            .expectStatus().isUnauthorized();
        client.get().uri("/api/budgets/1").exchange()
            .expectStatus().isUnauthorized();

        Assertions.assertEquals(0, BACKEND.hits());
    }

    @Test
    void projectsAndBudgetsAreRoutedToBudgetService() {
        client.get().uri("/api/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()).exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("ok");
        client.get().uri("/api/budgets/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()).exchange()
            .expectStatus().isOk();

        Assertions.assertEquals(2, BACKEND.hits());
    }

    @Test
    void failingConnectionsAnswerWithTheBudgetServiceFallback() {
        BACKEND.use(StubBackend.dropFirst(Integer.MAX_VALUE));

        client.get().uri("/api/projects").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken()).exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.message").isEqualTo("budget-service is temporarily unavailable. Please try again in a few moments.");
    }

    /** An access token as user-service issues it: jti = user id, sub = email, type = access. */
    private static String accessToken() {
        return Jwts.builder()
            .id("42")
            .subject("test@example.com")
            .claims(Map.of("name", "Test", "type", "access"))
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
            .compact();
    }
}
