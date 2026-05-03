package com.chernandez.gatewayservice.filter;

import com.chernandez.gatewayservice.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;

@Component
@RequiredArgsConstructor
public class JwtAuthGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGatewayFilter.class);

    private final JwtService jwtService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${application.security.open-endpoints:/auth/**}")
    private String openEndpoints;

    @Value("${application.security.internal-api-key}")
    private String internalApiKey;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        log.info("Gateway request: {} {}", request.getMethod(), path);

        if (isOpenEndpoint(path)) {
            log.debug("Open endpoint, skipping JWT validation: {}", path);
            ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-Internal-Api-Key", internalApiKey)
                .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return onError(exchange, "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        try {
            String username = jwtService.extractUsername(token);
            if (username == null) {
                return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
            }

            if (!jwtService.isTokenValid(token)) {
                return onError(exchange, "Expired or invalid JWT token", HttpStatus.UNAUTHORIZED);
            }

            // Forward user info and internal key as headers to downstream services
            ServerHttpRequest mutatedRequest = request.mutate()
                .header("X-User-Email", username)
                .header("X-User-Id", jwtService.extractUserId(token))
                .header("X-Internal-Api-Key", internalApiKey)
                .build();

            log.info("JWT validated for user: {}", username);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception ex) {
            log.error("JWT validation failed: {}", ex.getMessage());
            return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
        }
    }

    @Override
    public int getOrder() {
        return -1;
    }

    private boolean isOpenEndpoint(String path) {
        return Arrays.stream(openEndpoints.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> onError(ServerWebExchange exchange, String message, HttpStatus status) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"message\":\"" + message + "\",\"data\":null}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
