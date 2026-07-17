package com.seatsync.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end auth flow against a real Postgres (Flyway migration + seeding).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.enabled=false")
class AuthFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void fullAuthFlow_registerLoginMeRefreshRotationAndDuplicate() {
        // --- register ---
        ResponseEntity<JsonNode> registered = postJson("/api/auth/register", Map.of(
                "email", "jane@example.com",
                "password", "s3cret-pass!",
                "fullName", "Jane Doe",
                "role", "ATTENDEE"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode registeredBody = registered.getBody();
        assertThat(registeredBody).isNotNull();
        assertThat(registeredBody.get("id").asText()).isNotBlank();
        assertThat(registeredBody.get("email").asText()).isEqualTo("jane@example.com");
        assertThat(registeredBody.get("fullName").asText()).isEqualTo("Jane Doe");
        assertThat(registeredBody.get("roles").get(0).asText()).isEqualTo("ATTENDEE");
        assertThat(registeredBody.has("password")).isFalse();
        assertThat(registeredBody.has("passwordHash")).isFalse();

        // --- duplicate register -> 409 ProblemDetail ---
        ResponseEntity<JsonNode> duplicate = postJson("/api/auth/register", Map.of(
                "email", "jane@example.com",
                "password", "s3cret-pass!",
                "fullName", "Jane Clone",
                "role", "ATTENDEE"));
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody()).isNotNull();
        assertThat(duplicate.getBody().get("status").asInt()).isEqualTo(409);
        assertThat(duplicate.getBody().get("title").asText()).isEqualTo("Conflict");

        // --- login ---
        ResponseEntity<JsonNode> login = postJson("/api/auth/login", Map.of(
                "email", "jane@example.com",
                "password", "s3cret-pass!"));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode loginBody = login.getBody();
        assertThat(loginBody).isNotNull();
        String accessToken = loginBody.get("accessToken").asText();
        String refreshToken = loginBody.get("refreshToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(loginBody.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(loginBody.get("expiresInSeconds").asLong()).isEqualTo(900L);
        assertThat(loginBody.get("user").get("email").asText()).isEqualTo("jane@example.com");
        assertThat(loginBody.get("user").get("fullName").asText()).isEqualTo("Jane Doe");

        // --- me (with access token) ---
        ResponseEntity<JsonNode> me = getWithBearer("/api/auth/me", accessToken);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody()).isNotNull();
        assertThat(me.getBody().get("id").asText()).isEqualTo(registeredBody.get("id").asText());
        assertThat(me.getBody().get("email").asText()).isEqualTo("jane@example.com");
        assertThat(me.getBody().get("fullName").asText()).isEqualTo("Jane Doe");
        assertThat(me.getBody().get("roles").get(0).asText()).isEqualTo("ATTENDEE");

        // --- me with the refresh token must be rejected (typ != access) ---
        ResponseEntity<JsonNode> meWithRefresh = getWithBearer("/api/auth/me", refreshToken);
        assertThat(meWithRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // --- refresh (rotation) ---
        ResponseEntity<JsonNode> refreshed = postJson("/api/auth/refresh", Map.of("refreshToken", refreshToken));
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode refreshedBody = refreshed.getBody();
        assertThat(refreshedBody).isNotNull();
        String newAccessToken = refreshedBody.get("accessToken").asText();
        String newRefreshToken = refreshedBody.get("refreshToken").asText();
        assertThat(newRefreshToken).isNotBlank().isNotEqualTo(refreshToken);
        assertThat(refreshedBody.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(refreshedBody.get("user").get("email").asText()).isEqualTo("jane@example.com");

        // new access token works
        assertThat(getWithBearer("/api/auth/me", newAccessToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        // --- reuse of the OLD (revoked) refresh token -> 401 ---
        ResponseEntity<JsonNode> reuse = postJson("/api/auth/refresh", Map.of("refreshToken", refreshToken));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody()).isNotNull();
        assertThat(reuse.getBody().get("status").asInt()).isEqualTo(401);

        // the rotated refresh token still works exactly once more
        ResponseEntity<JsonNode> secondRotation = postJson("/api/auth/refresh", Map.of("refreshToken", newRefreshToken));
        assertThat(secondRotation.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void registerWithAdminRoleIsForbidden() {
        ResponseEntity<JsonNode> response = postJson("/api/auth/register", Map.of(
                "email", "evil@example.com",
                "password", "s3cret-pass!",
                "fullName", "Evil Admin",
                "role", "ADMIN"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asInt()).isEqualTo(403);
    }

    @Test
    void registerValidationFailureReturns400WithFieldErrors() {
        ResponseEntity<JsonNode> response = postJson("/api/auth/register", Map.of(
                "email", "not-an-email",
                "password", "short",
                "fullName", "",
                "role", "ATTENDEE"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status").asInt()).isEqualTo(400);
        assertThat(body.get("errors").has("email")).isTrue();
        assertThat(body.get("errors").has("password")).isTrue();
        assertThat(body.get("errors").has("fullName")).isTrue();
    }

    @Test
    void loginWithBadPasswordReturns401() {
        ResponseEntity<JsonNode> response = postJson("/api/auth/login", Map.of(
                "email", "attendee@seatsync.local",
                "password", "wrong-password"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asInt()).isEqualTo(401);
    }

    @Test
    void meWithoutTokenReturns401ProblemDetail() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/auth/me", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status").asInt()).isEqualTo(401);
    }

    @Test
    void seededUsersExistWithContractUuidsAndPasswords() {
        assertSeededLogin("admin@seatsync.local", "admin123!",
                "00000000-0000-0000-0000-000000000001", "ADMIN");
        assertSeededLogin("organizer@seatsync.local", "organizer1!",
                "00000000-0000-0000-0000-000000000002", "ORGANIZER");
        assertSeededLogin("attendee@seatsync.local", "attendee1!",
                "00000000-0000-0000-0000-000000000003", "ATTENDEE");
    }

    private void assertSeededLogin(String email, String password, String expectedId, String expectedRole) {
        ResponseEntity<JsonNode> login = postJson("/api/auth/login", Map.of("email", email, "password", password));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode user = login.getBody().get("user");
        assertThat(user.get("id").asText()).isEqualTo(expectedId);
        assertThat(user.get("roles").get(0).asText()).isEqualTo(expectedRole);
    }

    private ResponseEntity<JsonNode> postJson(String path, Map<String, ?> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(RequestEntity.post(URI.create(path)).headers(headers).body(body), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getWithBearer(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(new RequestEntity<>(headers, HttpMethod.GET, URI.create(path)), JsonNode.class);
    }
}
