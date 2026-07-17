package com.seatsync.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Edge JWT validation (CONVENTIONS §4 / §6.7).
 *
 * Runs before routing; skips CORS preflights (OPTIONS) and the exact public
 * path list from §4. Everything else requires a valid, unexpired HS256 access
 * token ({@code typ=access}). Failures produce an RFC 7807
 * {@code application/problem+json} 401 body (§5). On success the request is
 * forwarded with the {@code Authorization} header untouched (downstream
 * services re-validate — defense in depth).
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    /** Run well before route-scoped filters (which are ordered from 1). */
    public static final int ORDER = -100;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern EVENT_SEATS_PATTERN = Pattern.compile("^/api/events/[^/]+/seats$");

    private final ObjectMapper objectMapper;
    private final SecretKey key;

    public JwtAuthGlobalFilter(ObjectMapper objectMapper, @Value("${jwt.secret}") String secret) {
        this.objectMapper = objectMapper;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpMethod method = request.getMethod();
        String path = request.getPath().value();

        if (HttpMethod.OPTIONS.equals(method) || isPublic(method, path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"access".equals(claims.get("typ", String.class))) {
                return unauthorized(exchange, "Token is not an access token");
            }
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "Access token has expired");
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange, "Invalid access token");
        }

        // Valid: pass through, Authorization header untouched.
        return chain.filter(exchange);
    }

    /**
     * Public paths per CONVENTIONS §4, matched exactly:
     * POST /api/auth/register|login|refresh, GET /api/catalog/**,
     * GET /api/events/*&#47;seats, /ws/**, POST /api/concierge/chat,
     * /actuator/health.
     */
    private boolean isPublic(HttpMethod method, String path) {
        if (path.equals("/actuator/health") || path.startsWith("/actuator/health/")) {
            return true;
        }
        if (path.equals("/ws") || path.startsWith("/ws/")) {
            return true;
        }
        if (HttpMethod.POST.equals(method)) {
            return path.equals("/api/auth/register")
                    || path.equals("/api/auth/login")
                    || path.equals("/api/auth/refresh")
                    || path.equals("/api/concierge/chat");
        }
        if (HttpMethod.GET.equals(method)) {
            return path.equals("/api/catalog")
                    || path.startsWith("/api/catalog/")
                    || EVENT_SEATS_PATTERN.matcher(path).matches();
        }
        return false;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Unauthorized");
        problem.put("status", 401);
        problem.put("detail", detail);
        problem.put("instance", exchange.getRequest().getPath().value());

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            body = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
