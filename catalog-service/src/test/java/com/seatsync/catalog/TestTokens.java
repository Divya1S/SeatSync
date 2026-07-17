package com.seatsync.catalog;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints access tokens exactly like auth-service does (CONVENTIONS §4), signed with the
 * default dev secret.
 */
public final class TestTokens {

    public static final String DEV_SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";

    private TestTokens() {
    }

    public static String accessToken(UUID userId, String email, String name, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(DEV_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .claim("typ", "access")
                .issuer("seatsync-auth")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
