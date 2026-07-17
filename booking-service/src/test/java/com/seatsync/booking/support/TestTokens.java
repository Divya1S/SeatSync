package com.seatsync.booking.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Mints real access tokens with the shared dev secret (conventions section 4),
 * exactly as auth-service would.
 */
public final class TestTokens {

    public static final String DEV_SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(DEV_SECRET.getBytes(StandardCharsets.UTF_8));

    private TestTokens() {
    }

    public static String accessToken(UUID userId, String email, String name, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim("name", name)
                .claim("roles", roles)
                .claim("typ", "access")
                .issuer("seatsync-auth")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }

    public static String attendee(UUID userId, String email) {
        return accessToken(userId, email, "Test Attendee", List.of("ATTENDEE"));
    }
}
