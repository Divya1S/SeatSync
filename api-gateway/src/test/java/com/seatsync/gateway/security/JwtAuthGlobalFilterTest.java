package com.seatsync.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthGlobalFilterTest {

    private static final String SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private JwtAuthGlobalFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthGlobalFilter(new ObjectMapper(), SECRET);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ---- public paths skip auth ----

    @Test
    void getCatalogIsPublic() {
        assertPassesThrough(MockServerHttpRequest.get("/api/catalog/events").build());
        assertPassesThrough(MockServerHttpRequest.get("/api/catalog/events/abc/seatmap").build());
    }

    @Test
    void postCatalogRequiresAuth() {
        assertUnauthorized(MockServerHttpRequest.post("/api/catalog/events").build());
    }

    @Test
    void authEndpointsArePublicForPost() {
        assertPassesThrough(MockServerHttpRequest.post("/api/auth/register").build());
        assertPassesThrough(MockServerHttpRequest.post("/api/auth/login").build());
        assertPassesThrough(MockServerHttpRequest.post("/api/auth/refresh").build());
    }

    @Test
    void authMeRequiresAuth() {
        assertUnauthorized(MockServerHttpRequest.get("/api/auth/me").build());
    }

    @Test
    void getEventSeatsIsPublic() {
        assertPassesThrough(MockServerHttpRequest.get(
                "/api/events/" + UUID.randomUUID() + "/seats").build());
    }

    @Test
    void getEventStatsRequiresAuth() {
        assertUnauthorized(MockServerHttpRequest.get(
                "/api/events/" + UUID.randomUUID() + "/stats").build());
    }

    @Test
    void webSocketPathIsPublic() {
        assertPassesThrough(MockServerHttpRequest.get("/ws").build());
        assertPassesThrough(MockServerHttpRequest.get("/ws/123/abc/websocket").build());
    }

    @Test
    void conciergeChatIsPublicForPost() {
        assertPassesThrough(MockServerHttpRequest.post("/api/concierge/chat").build());
    }

    @Test
    void conciergeReindexRequiresAuth() {
        assertUnauthorized(MockServerHttpRequest.post("/api/concierge/reindex").build());
    }

    @Test
    void actuatorHealthIsPublic() {
        assertPassesThrough(MockServerHttpRequest.get("/actuator/health").build());
        assertPassesThrough(MockServerHttpRequest.get("/actuator/health/liveness").build());
    }

    @Test
    void optionsRequestsAreSkipped() {
        assertPassesThrough(MockServerHttpRequest.options("/api/bookings/mine").build());
    }

    // ---- protected paths ----

    @Test
    void missingTokenReturns401ProblemJson() {
        MockServerWebExchange exchange = assertUnauthorized(
                MockServerHttpRequest.get("/api/bookings/mine").build());

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"type\":\"about:blank\"")
                .contains("\"title\":\"Unauthorized\"")
                .contains("\"status\":401")
                .contains("\"instance\":\"/api/bookings/mine\"");
    }

    @Test
    void nonBearerAuthorizationReturns401() {
        assertUnauthorized(MockServerHttpRequest.get("/api/bookings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz")
                .build());
    }

    @Test
    void expiredTokenReturns401() {
        String expired = token("access", Instant.now().minusSeconds(60));
        MockServerWebExchange exchange = assertUnauthorized(
                MockServerHttpRequest.get("/api/bookings/mine")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .build());
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("expired");
    }

    @Test
    void refreshTokenReturns401() {
        String refresh = token("refresh", Instant.now().plusSeconds(600));
        assertUnauthorized(MockServerHttpRequest.get("/api/bookings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)
                .build());
    }

    @Test
    void garbageTokenReturns401() {
        assertUnauthorized(MockServerHttpRequest.get("/api/bookings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
                .build());
    }

    @Test
    void tokenSignedWithWrongKeyReturns401() {
        String wrongKeyToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("typ", "access")
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(
                        "another-secret-key-that-is-long-enough-000000".getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256)
                .compact();
        assertUnauthorized(MockServerHttpRequest.get("/api/bookings/mine")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongKeyToken)
                .build());
    }

    @Test
    void validAccessTokenPassesThroughWithAuthorizationUntouched() {
        String valid = token("access", Instant.now().plusSeconds(600));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bookings/mine")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + valid)
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        assertThat(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer " + valid);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    // ---- helpers ----

    private void assertPassesThrough(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private MockServerWebExchange assertUnauthorized(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        return exchange;
    }

    private String token(String typ, Instant expiresAt) {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", "attendee@seatsync.local")
                .claim("name", "Demo Attendee")
                .claim("roles", List.of("ATTENDEE"))
                .claim("typ", typ)
                .issuer("seatsync-auth")
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(expiresAt))
                .signWith(KEY, Jwts.SIG.HS256)
                .compact();
    }
}
