package com.seatsync.concierge.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Parse-side JWT validation (HS256, shared secret) per CONVENTIONS §4. This
 * service only verifies tokens issued by auth-service ({@code iss=seatsync-auth}).
 */
@Service
public class JwtService {

    public static final String ISSUER = "seatsync-auth";
    public static final String TYP_ACCESS = "access";

    private final SecretKey key;

    public JwtService(@Value("${seatsync.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Parses and validates signature, expiry and issuer.
     *
     * @throws io.jsonwebtoken.JwtException if the token is invalid, expired or tampered with
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
