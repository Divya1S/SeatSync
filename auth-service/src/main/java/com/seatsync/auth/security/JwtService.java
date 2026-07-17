package com.seatsync.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and parses SeatSync JWTs (HS256, shared secret).
 *
 * <p>Claims per CONVENTIONS §4: {@code sub} = userId, {@code email},
 * {@code name}, {@code roles} (JSON array without ROLE_ prefix),
 * {@code typ} = access|refresh, {@code iss} = seatsync-auth.
 */
@Service
public class JwtService {

    public static final String ISSUER = "seatsync-auth";
    public static final String TYP_ACCESS = "access";
    public static final String TYP_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(@Value("${seatsync.jwt.secret}") String secret,
                      @Value("${seatsync.jwt.access-ttl-seconds}") long accessTtlSeconds,
                      @Value("${seatsync.jwt.refresh-ttl-seconds}") long refreshTtlSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public long getAccessTtlSeconds() {
        return accessTtlSeconds;
    }

    public long getRefreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    public String issueAccessToken(UUID userId, String email, String fullName, List<String> roles) {
        return issue(userId, email, fullName, roles, TYP_ACCESS, accessTtlSeconds);
    }

    public String issueRefreshToken(UUID userId, String email, String fullName, List<String> roles) {
        return issue(userId, email, fullName, roles, TYP_REFRESH, refreshTtlSeconds);
    }

    private String issue(UUID userId, String email, String fullName, List<String> roles, String typ, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                // unique id so two tokens issued in the same second never collide
                // (token_hash is unique in refresh_tokens)
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("email", email)
                .claim("name", fullName)
                .claim("roles", roles)
                .claim("typ", typ)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Parses and validates signature, expiry and issuer.
     *
     * @throws JwtException if the token is invalid, expired or tampered with
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    public List<String> rolesFrom(Claims claims) {
        List<Object> raw = claims.get("roles", List.class);
        return raw == null ? List.of() : raw.stream().map(String::valueOf).toList();
    }
}
