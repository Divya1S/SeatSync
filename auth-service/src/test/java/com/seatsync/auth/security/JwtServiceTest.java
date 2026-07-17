package com.seatsync.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";

    private final JwtService jwtService = new JwtService(SECRET, 900, 604800);

    private final UUID userId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void accessTokenRoundTripCarriesAllClaims() {
        String token = jwtService.issueAccessToken(
                userId, "jane@example.com", "Jane Doe", List.of("ATTENDEE", "ORGANIZER"));

        Claims claims = jwtService.parse(token);

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("seatsync-auth");
        assertThat(claims.get("email", String.class)).isEqualTo("jane@example.com");
        assertThat(claims.get("name", String.class)).isEqualTo("Jane Doe");
        assertThat(claims.get("typ", String.class)).isEqualTo("access");
        assertThat(jwtService.rolesFrom(claims)).containsExactly("ATTENDEE", "ORGANIZER");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime()).isEqualTo(900_000L);
    }

    @Test
    void refreshTokenHasRefreshTyp() {
        String token = jwtService.issueRefreshToken(userId, "jane@example.com", "Jane Doe", List.of("ATTENDEE"));

        Claims claims = jwtService.parse(token);

        assertThat(claims.get("typ", String.class)).isEqualTo("refresh");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expiredIssuer = new JwtService(SECRET, -60, -60);
        String token = expiredIssuer.issueAccessToken(userId, "jane@example.com", "Jane Doe", List.of("ATTENDEE"));

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tokenSignedWithWrongSecretIsRejected() {
        JwtService other = new JwtService("another-secret-that-is-long-enough-0123456789", 900, 604800);
        String token = other.issueAccessToken(userId, "jane@example.com", "Jane Doe", List.of("ATTENDEE"));

        assertThatThrownBy(() -> jwtService.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageTokenIsRejected() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt")).isInstanceOf(JwtException.class);
    }
}
