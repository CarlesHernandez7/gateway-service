package com.chernandez.gatewayservice;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/** Gateway behaviour when user-service is not running at all (connection refused). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "JWT_SECRET_KEY=VGhpc0lzQVRlc3RLZXlGb3JHYXRld2F5U2VydmljZUFuZEl0SXNMb25nRW5vdWdo",
    "INTERNAL_API_KEY=test-internal-key"
})
class ResilienceBackendDownTests {

    @DynamicPropertySource
    static void closedPort(DynamicPropertyRegistry registry) throws IOException {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        registry.add("USER_SERVICE_URL", () -> "http://localhost:" + freePort);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ReactiveResilience4JCircuitBreakerFactory circuitBreakerFactory;

    @Test
    void answers503WithTheFallbackAndKeepsTheCorsHeaders() {
        client().get().uri("/auth/verify?token=x")
            .header("Origin", "http://localhost:3000")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000")
            .expectBody()
            .jsonPath("$.message").value(message -> Assertions.assertTrue(message.toString().contains("temporarily unavailable")));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndKeepsAnswering503() {
        WebTestClient client = client();
        for (int call = 0; call < 8; call++) {
            client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(503);
        }

        CircuitBreaker circuitBreaker = circuitBreakerFactory.getCircuitBreakerRegistry()
            .getAllCircuitBreakers().stream()
            .filter(breaker -> breaker.getName().contains("userService"))
            .findFirst().orElseThrow();
        Assertions.assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    }
}
