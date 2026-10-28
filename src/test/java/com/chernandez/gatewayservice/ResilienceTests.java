package com.chernandez.gatewayservice;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

/**
 * Gateway behaviour against a user-service that is slow, stalled, failing or dropping connections.
 * The response timeout is lowered to 3s so the stalled case does not make the suite slow.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "JWT_SECRET_KEY=VGhpc0lzQVRlc3RLZXlGb3JHYXRld2F5U2VydmljZUFuZEl0SXNMb25nRW5vdWdo",
    "INTERNAL_API_KEY=test-internal-key",
    "GATEWAY_RESPONSE_TIMEOUT=3s"
})
class ResilienceTests {

    private static final StubBackend BACKEND = new StubBackend();

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("USER_SERVICE_URL", BACKEND::url);
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
    void healthyRequestsAreProxied() {
        client.get().uri("/auth/verify?token=x").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void slowButHealthyResponseIsNotCutByResilience4jDefaultOneSecondLimit() {
        BACKEND.use(StubBackend.slow(1500));

        client.get().uri("/auth/verify?token=x").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("ok");
    }

    @Test
    void stalledBackendGetsTheFallbackAfterTheResponseTimeoutAndIsNotRetried() {
        BACKEND.use(StubBackend.slow(8000));

        long start = System.nanoTime();
        client.get().uri("/auth/verify?token=x").exchange()
            .expectStatus().isEqualTo(503)
            .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
            .expectBody()
            .jsonPath("$.message").value(message -> Assertions.assertTrue(message.toString().contains("temporarily unavailable")))
            .jsonPath("$.data").isEmpty();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        Assertions.assertTrue(elapsed.compareTo(Duration.ofSeconds(7)) < 0,
            "the gateway must answer once the 3s response timeout expires, took " + elapsed);
        Assertions.assertEquals(1, BACKEND.hits(), "a timeout must not be retried");
    }

    @Test
    void serverErrorsFromTheServiceArePassedThroughAndNotRetried() {
        BACKEND.use(StubBackend.status(500));

        client.get().uri("/auth/verify?token=x").exchange()
            .expectStatus().isEqualTo(500)
            .expectBody(String.class).isEqualTo("status 500");
        Assertions.assertEquals(1, BACKEND.hits());
    }

    @Test
    void getIsRetriedWhenTheConnectionIsDropped() {
        BACKEND.use(StubBackend.dropFirst(1));

        client.get().uri("/auth/verify?token=x").exchange()
            .expectStatus().isOk()
            .expectBody(String.class).isEqualTo("ok");
        Assertions.assertEquals(2, BACKEND.hits());
    }

    @Test
    void postIsNeverRetried() {
        BACKEND.use(StubBackend.dropFirst(1));

        client.post().uri("/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
            .expectStatus().isEqualTo(503);
        Assertions.assertEquals(1, BACKEND.hits(), "a POST must reach the service at most once");
    }
}
