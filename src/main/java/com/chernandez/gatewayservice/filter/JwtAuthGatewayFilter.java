package com.chernandez.gatewayservice.filter;

import com.chernandez.gatewayservice.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class JwtAuthGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGatewayFilter.class);

    private static final String USER_EMAIL_HEADER = "X-User-Email";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String CLIENT_IP_HEADER = "X-Client-Ip";

    private final JwtService jwtService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> openEndpoints;
    private final String internalApiKey;

    public JwtAuthGatewayFilter(
        JwtService jwtService,
        @Value("${application.security.open-endpoints:/auth/**}") String openEndpoints,
        @Value("${application.security.internal-api-key}") String internalApiKey
    ) {
        this.jwtService = jwtService;
        this.openEndpoints = Arrays.stream(openEndpoints.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toList();
        this.internalApiKey = internalApiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        log.debug("Gateway request: {} {}", request.getMethod(), path);

        // Reject path traversal so "/auth/../api/..." cannot bypass JWT validation
        if (path.contains("..")) {
            return onError(exchange, "Invalid request path", HttpStatus.BAD_REQUEST);
        }

        if (isOpenEndpoint(path)) {
            log.debug("Open endpoint, skipping JWT validation: {}", path);
            return chain.filter(forward(exchange, null, null));
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Missing or invalid Authorization header for path: {}", path);
            return onError(exchange, "Missing or invalid Authorization header", HttpStatus.UNAUTHORIZED);
        }

        Claims claims;
        try {
            claims = jwtService.extractAllClaims(authHeader.substring(7));
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("JWT validation failed: {}", ex.getMessage());
            return onError(exchange, "Expired or invalid JWT token", HttpStatus.UNAUTHORIZED);
        }

        if (claims.getSubject() == null || jwtService.isRefreshToken(claims)) {
            return onError(exchange, "Invalid JWT token", HttpStatus.UNAUTHORIZED);
        }

        log.debug("JWT validated for user: {}", claims.getSubject());
        return chain.filter(forward(exchange, claims.getSubject(), claims.getId()));
    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * Builds the downstream request. Identity headers sent by the client are always
     * discarded so they can only come from a validated JWT.
     */
    private ServerWebExchange forward(ServerWebExchange exchange, String email, String userId) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
            .headers(headers -> {
                headers.remove(USER_EMAIL_HEADER);
                headers.remove(USER_ID_HEADER);
                headers.remove(CLIENT_IP_HEADER);
                headers.set(INTERNAL_API_KEY_HEADER, internalApiKey);
                if (email != null) {
                    headers.set(USER_EMAIL_HEADER, email);
                }
                if (userId != null) {
                    headers.set(USER_ID_HEADER, userId);
                }
                if (remoteAddress != null && remoteAddress.getAddress() != null) {
                    headers.set(CLIENT_IP_HEADER, remoteAddress.getAddress().getHostAddress());
                }
            })
            .build();
        return exchange.mutate().request(mutatedRequest).build();
    }

    private boolean isOpenEndpoint(String path) {
        return openEndpoints.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
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
