package com.seatsync.concierge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.concierge.chat.ConciergeService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context integration test WITHOUT an OpenAI key and WITHOUT any network
 * call to OpenAI: the context must load against a real pgvector database,
 * /chat must return the graceful offline shape, reindex must be ADMIN-gated,
 * and actuator health must be UP.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Shadows any real key exported in the developer's shell: the AI must be OFF here.
                "OPENAI_API_KEY=sk-dummy-offline",
                "management.tracing.sampling.probability=0.0"
        })
class OfflineConciergeIT {

    private static final String JWT_SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void chatReturnsGracefulOfflineShape() throws Exception {
        ResponseEntity<String> response = postJson("/api/concierge/chat",
                "{\"message\":\"How do refunds work?\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("answer").asText()).isEqualTo(ConciergeService.OFFLINE_ANSWER);
        assertThat(body.path("answer").asText()).contains("support@seatsync.local");
        assertThat(body.path("confidence").asText()).isEqualTo("LOW");
        assertThat(body.path("escalatedToHuman").asBoolean()).isTrue();
        assertThat(body.path("sources").isArray()).isTrue();
        assertThat(body.path("sources")).isEmpty();
        assertThat(body.path("conversationId").asText()).isNotBlank();
    }

    @Test
    void chatKeepsProvidedConversationId() throws Exception {
        String conversationId = UUID.randomUUID().toString();

        ResponseEntity<String> response = postJson("/api/concierge/chat",
                "{\"message\":\"hello\",\"conversationId\":\"" + conversationId + "\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("conversationId").asText()).isEqualTo(conversationId);
    }

    @Test
    void blankMessageIsRejectedWithProblemDetail() throws Exception {
        ResponseEntity<String> response = postJson("/api/concierge/chat", "{\"message\":\"  \"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(400);
    }

    @Test
    void reindexRequiresAuthentication() {
        ResponseEntity<String> response = postJson("/api/concierge/reindex", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void reindexWithAdminTokenIs503WhileOffline() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken());
        ResponseEntity<String> response = rest.postForEntity("/api/concierge/reindex",
                new HttpEntity<>("", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("status").asInt()).isEqualTo(503);
        assertThat(body.path("detail").asText()).contains("offline");
    }

    @Test
    void actuatorHealthIsUpWithoutAuth() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void vectorStoreSchemaExistsAndIngestionWasSkipped() {
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Long.class);

        assertThat(rows).isZero();
    }

    private ResponseEntity<String> postJson(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private static String adminToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("seatsync-auth")
                .subject("00000000-0000-0000-0000-000000000001")
                .claim("email", "admin@seatsync.local")
                .claim("name", "SeatSync Admin")
                .claim("roles", List.of("ADMIN"))
                .claim("typ", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }
}
