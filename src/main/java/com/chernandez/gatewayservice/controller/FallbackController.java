package com.chernandez.gatewayservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Target of the {@code fallbackUri} of each route's CircuitBreaker filter. It answers with the same JSON
 * shape as the rest of the platform ({@code message}, {@code data}) and accepts any HTTP method, because
 * the original method is preserved when the request is forwarded here.
 */
@RestController
public class FallbackController {

    @RequestMapping("/fallback/user-service")
    public Mono<ResponseEntity<Map<String, Object>>> userServiceFallback() {
        return unavailable("user-service is temporarily unavailable. Please try again in a few moments.");
    }

    @RequestMapping("/fallback/budget-service")
    public Mono<ResponseEntity<Map<String, Object>>> budgetServiceFallback() {
        return unavailable("budget-service is temporarily unavailable. Please try again in a few moments.");
    }

    @RequestMapping("/fallback/organization-service")
    public Mono<ResponseEntity<Map<String, Object>>> organizationServiceFallback() {
        return unavailable("organization-service is temporarily unavailable. Please try again in a few moments.");
    }

    private Mono<ResponseEntity<Map<String, Object>>> unavailable(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("data", null);
        return Mono.just(ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body));
    }
}
