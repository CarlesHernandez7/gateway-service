package com.chernandez.gatewayservice.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final JwtParser parser;

    public JwtService(@Value("${application.security.jwt.secret-key}") String secretKey) {
        this.parser = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secretKey)))
            .build();
    }

    /**
     * Verifies signature and expiration and returns the claims.
     *
     * @throws io.jsonwebtoken.JwtException if the token is malformed, tampered or expired
     */
    public Claims extractAllClaims(String token) {
        return parser.parseSignedClaims(token).getPayload();
    }

    public boolean isRefreshToken(Claims claims) {
        return REFRESH_TOKEN_TYPE.equals(claims.get("type", String.class));
    }
}
