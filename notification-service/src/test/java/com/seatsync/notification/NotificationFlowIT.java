package com.seatsync.notification;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.seatsync.notification.domain.Notification;
import com.seatsync.notification.domain.NotificationRepository;
import com.seatsync.notification.domain.NotificationStatus;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end happy-path IT: Postgres + Kafka (Testcontainers) + GreenMail
 * SMTP. Publishes one JSON event per topic, expects three SENT rows and three
 * emails; then points SMTP at an unused port and expects a FAILED row that
 * self-heals to SENT via non-blocking retry once SMTP is back, with the
 * listener alive throughout. (DLT parking and poison classification are
 * covered by {@link RetryDltFlowIT}.) The context is dirtied after the class
 * so its listeners stop before the next IT's containers spin up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationFlowIT {

    private static final String JWT_SECRET = "seatsync-dev-secret-change-me-0123456789abcdef";
    private static final String EVENT_ID = "8d7c2c6e-3f1a-4a71-9a3b-111111111111";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> ServerSetupTest.SMTP.getPort());
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("management.zipkin.tracing.export.enabled", () -> "false");
    }

    // Topics come from the main KafkaTopicsConfig (identical NewTopic beans
    // to booking-service per CONVENTIONS §6.4) — no test-scoped duplicates.

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    NotificationRepository repository;

    @Autowired
    JavaMailSender mailSender;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @Order(1)
    void consumesAllThreeTopicsSendsEmailsAndPersistsSentRows() {
        kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-1-4","userId":"%s",
                 "userEmail":"attendee@seatsync.local","price":49.00,
                 "occurredAt":"2026-07-16T18:00:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));
        kafkaTemplate.send("seatsync.hold.expired", EVENT_ID, """
                {"type":"HoldExpired","messageId":"%s","holdId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"A-2-9","userId":"%s",
                 "userEmail":"slowpoke@seatsync.local","occurredAt":"2026-07-16T18:05:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));
        kafkaTemplate.send("seatsync.waitlist.offered", EVENT_ID, """
                {"type":"WaitlistOffered","messageId":"%s","eventId":"%s","eventName":"Friday Night Jazz",
                 "seatId":"A-1-4","userId":"%s","userEmail":"waiting@seatsync.local",
                 "offerExpiresAt":"2026-07-16T18:10:00Z","occurredAt":"2026-07-16T18:00:00Z"}
                """.formatted(UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> rows = repository.findAll();
            assertThat(rows).hasSize(3);
            assertThat(rows).allMatch(n -> n.getStatus() == NotificationStatus.SENT);
            assertThat(rows).allMatch(n -> n.getMessageId() != null);
            assertThat(rows).extracting(Notification::getType)
                    .containsExactlyInAnyOrder("BookingConfirmed", "HoldExpired", "WaitlistOffered");
        });

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(3));

        Set<String> subjects = Arrays.stream(greenMail.getReceivedMessages())
                .map(NotificationFlowIT::subjectOf)
                .collect(Collectors.toSet());
        assertThat(subjects).containsExactlyInAnyOrder(
                "Booking confirmed — Friday Night Jazz, seat A-1-4",
                "Seat hold expired — Friday Night Jazz, seat A-2-9",
                "A seat just opened up — Friday Night Jazz, seat A-1-4");
    }

    @Test
    @Order(2)
    void recentEndpointRequiresAdmin() {
        ResponseEntity<String> anonymous =
                restTemplate.getForEntity("/api/notifications/recent", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<String> attendee = restTemplate.exchange("/api/notifications/recent",
                HttpMethod.GET, withBearer(token("ATTENDEE")), String.class);
        assertThat(attendee.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<List<Map<String, Object>>> admin = restTemplate.exchange(
                "/api/notifications/recent?limit=50", HttpMethod.GET, withBearer(token("ADMIN")),
                new org.springframework.core.ParameterizedTypeReference<>() {
                });
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(admin.getBody()).isNotNull();
        assertThat(admin.getBody()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(admin.getBody().getFirst()).containsKeys("id", "type", "recipient", "subject",
                "status", "createdAt");
    }

    @Test
    @Order(3)
    void mailFailureMarksRowFailedThenNonBlockingRetryRecovers() throws Exception {
        // §6.5: a mail transport failure marks the row FAILED and THROWS; the
        // retry-topic machinery redelivers non-blockingly (1s/2s/4s). Once
        // SMTP is back, a retry re-claims the FAILED row and flips it to SENT
        // — the main listener is never blocked.
        JavaMailSenderImpl senderImpl = (JavaMailSenderImpl) mailSender;
        int originalPort = senderImpl.getPort();
        try {
            senderImpl.setPort(unusedPort());

            kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, """
                    {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                     "eventName":"Friday Night Jazz","seatId":"B-3-2","userId":"%s",
                     "userEmail":"unlucky@seatsync.local","price":25.00,
                     "occurredAt":"2026-07-16T18:20:00Z"}
                    """.formatted(UUID.randomUUID(), UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

            await().atMost(TIMEOUT).untilAsserted(() -> {
                List<Notification> failed = rowsFor("unlucky@seatsync.local");
                assertThat(failed).hasSize(1);
                assertThat(failed.getFirst().getStatus()).isEqualTo(NotificationStatus.FAILED);
            });
        } finally {
            senderImpl.setPort(originalPort);
        }

        // A retry-topic redelivery re-claims the FAILED row (same row — no
        // duplicate) and self-heals to SENT now that SMTP is reachable again.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> healed = rowsFor("unlucky@seatsync.local");
            assertThat(healed).hasSize(1);
            assertThat(healed.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        });

        // Listener must still be consuming: the next message goes through fine.
        kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"B-3-3","userId":"%s",
                 "userEmail":"recovered@seatsync.local","price":25.00,
                 "occurredAt":"2026-07-16T18:25:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> sent = rowsFor("recovered@seatsync.local");
            assertThat(sent).hasSize(1);
            assertThat(sent.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        });
        // Two emails: the healed retry for unlucky@ plus recovered@.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(2));
    }

    @Test
    @Order(4)
    void duplicateMessageIdSendsExactlyOneEmailAndOneRow() {
        UUID messageId = UUID.randomUUID();
        String duplicatedPayload = """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"C-1-1","userId":"%s",
                 "userEmail":"dedupe@seatsync.local","price":42.00,
                 "occurredAt":"2026-07-16T19:00:00Z"}
                """.formatted(messageId, UUID.randomUUID(), EVENT_ID, UUID.randomUUID());

        // Same message (same messageId) delivered twice — an outbox retry /
        // rebalance redelivery. Same key → same partition → processed in order.
        kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, duplicatedPayload);
        kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, duplicatedPayload);
        // Legacy marker WITHOUT messageId (pre-contract payload), same key, so
        // it is processed strictly after both duplicate deliveries.
        kafkaTemplate.send("seatsync.booking.confirmed", EVENT_ID, """
                {"type":"BookingConfirmed","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"C-1-2","userId":"%s",
                 "userEmail":"legacy@seatsync.local","price":42.00,
                 "occurredAt":"2026-07-16T19:01:00Z"}
                """.formatted(UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

        // Legacy path still works — and once the marker is SENT, both duplicate
        // deliveries have already been fully processed.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> legacyRows = rowsFor("legacy@seatsync.local");
            assertThat(legacyRows).hasSize(1);
            assertThat(legacyRows.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(legacyRows.getFirst().getMessageId()).isNull();
        });

        // Exactly one row for the duplicated message, claimed under its messageId.
        List<Notification> dedupeRows = rowsFor("dedupe@seatsync.local");
        assertThat(dedupeRows).hasSize(1);
        assertThat(dedupeRows.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(dedupeRows.getFirst().getMessageId()).isEqualTo(messageId);

        // Exactly two emails in this test (GreenMail resets per test): one for
        // the deduped message, one for the legacy marker — no duplicate send.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(2));
        List<String> recipients = Arrays.stream(greenMail.getReceivedMessages())
                .map(NotificationFlowIT::recipientOf)
                .toList();
        assertThat(recipients).containsExactlyInAnyOrder(
                "dedupe@seatsync.local", "legacy@seatsync.local");
    }

    private static String recipientOf(MimeMessage message) {
        try {
            return message.getAllRecipients()[0].toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Notification> rowsFor(String recipient) {
        return repository.findAll().stream()
                .filter(n -> recipient.equals(n.getRecipient()))
                .toList();
    }

    private static String subjectOf(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpEntity<Void> withBearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private static String token(String role) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("email", role.toLowerCase() + "@seatsync.local")
                .claim("name", "Test " + role)
                .claim("roles", List.of(role))
                .claim("typ", "access")
                .issuer("seatsync-auth")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
