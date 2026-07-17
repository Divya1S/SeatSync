package com.seatsync.notification;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.seatsync.notification.domain.Notification;
import com.seatsync.notification.domain.NotificationRepository;
import com.seatsync.notification.domain.NotificationStatus;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
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

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Retry-topic + DLT IT (§6.5), with its own containers.
 *
 * <p>(a) Transient path: SMTP unreachable → row FAILED, the record is
 * redelivered via the retry topics (4 total attempts, exponential 1s ×2) and
 * parks in {@code <topic>-dlt}; replaying the SAME messageId after fixing
 * SMTP flips the SAME row to SENT (FAILED-retry claim semantics — replay is
 * safe at any time).
 *
 * <p>(b) Poison path: garbage JSON goes straight to the DLT (classified
 * non-retryable — no retry-topic deliveries), and the listener stays alive
 * for the next valid message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RetryDltFlowIT {

    private static final String EVENT_ID = "8d7c2c6e-3f1a-4a71-9a3b-222222222222";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static final String BOOKING_TOPIC = "seatsync.booking.confirmed";
    private static final String BOOKING_DLT = BOOKING_TOPIC + "-dlt";
    // Exponential backoff 1s ×2 → one retry topic per distinct interval
    // (spring-kafka's SINGLE_TOPIC strategy merges only same-interval hops).
    private static final List<String> BOOKING_RETRY_TOPICS = List.of(
            BOOKING_TOPIC + "-retry-1000",
            BOOKING_TOPIC + "-retry-2000",
            BOOKING_TOPIC + "-retry-4000");

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

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    NotificationRepository repository;

    @Autowired
    JavaMailSender mailSender;

    @Test
    @Order(1)
    void transientFailureRetriesThenParksInDltAndReplayOfSameMessageIdRecovers() throws Exception {
        // Retry/DLT topics are auto-created (3 partitions, RF 1 per §6.4).
        await().atMost(TIMEOUT).untilAsserted(() -> {
            try (AdminClient admin = newAdmin()) {
                Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
                assertThat(topics)
                        .contains(BOOKING_DLT, "seatsync.hold.expired-dlt", "seatsync.waitlist.offered-dlt")
                        .containsAll(BOOKING_RETRY_TOPICS);
                assertThat(admin.describeTopics(List.of(BOOKING_DLT)).allTopicNames()
                        .get(10, TimeUnit.SECONDS).get(BOOKING_DLT).partitions()).hasSize(3);
            }
        });

        UUID messageId = UUID.randomUUID();
        String payload = """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"D-1-1","userId":"%s",
                 "userEmail":"mail-down@seatsync.local","price":30.00,
                 "occurredAt":"2026-07-16T18:30:00Z"}
                """.formatted(messageId, UUID.randomUUID(), EVENT_ID, UUID.randomUUID());

        JavaMailSenderImpl senderImpl = (JavaMailSenderImpl) mailSender;
        int originalPort = senderImpl.getPort();
        ConsumerRecord<String, String> dltRecord;
        try {
            senderImpl.setPort(unusedPort());
            kafkaTemplate.send(BOOKING_TOPIC, EVENT_ID, payload);

            // First attempt claims the row and marks it FAILED.
            await().atMost(TIMEOUT).untilAsserted(() -> {
                List<Notification> rows = rowsFor("mail-down@seatsync.local");
                assertThat(rows).hasSize(1);
                assertThat(rows.getFirst().getStatus()).isEqualTo(NotificationStatus.FAILED);
                assertThat(rows.getFirst().getMessageId()).isEqualTo(messageId);
            });

            // After retries exhaust, the record parks in the DLT with the
            // original payload and exception headers.
            dltRecord = awaitRecord(BOOKING_DLT, r -> r.value().contains(messageId.toString()), TIMEOUT);
        } finally {
            senderImpl.setPort(originalPort);
        }
        assertThat(dltRecord.value()).isEqualTo(payload);
        assertThat(exceptionInfo(dltRecord)).contains("MailSend");

        // 4 total attempts = 1 main + exactly one delivery per retry topic.
        List<ConsumerRecord<String, String>> retryDeliveries =
                drain(BOOKING_RETRY_TOPICS, Duration.ofSeconds(3)).stream()
                        .filter(r -> r.value().contains(messageId.toString()))
                        .toList();
        assertThat(retryDeliveries).hasSize(3);
        assertThat(retryDeliveries).extracting(ConsumerRecord::topic)
                .containsExactlyInAnyOrderElementsOf(BOOKING_RETRY_TOPICS);

        // Row is left FAILED by the DLT handler.
        assertThat(rowsFor("mail-down@seatsync.local").getFirst().getStatus())
                .isEqualTo(NotificationStatus.FAILED);
        assertThat(greenMail.getReceivedMessages()).isEmpty();

        // DLT replay: SMTP fixed, republish the SAME messageId — the FAILED
        // claim is re-used and the SAME row flips to SENT, email delivered.
        kafkaTemplate.send(BOOKING_TOPIC, EVENT_ID, payload);
        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> rows = rowsFor("mail-down@seatsync.local");
            assertThat(rows).hasSize(1); // same row, no duplicate
            assertThat(rows.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
            assertThat(rows.getFirst().getMessageId()).isEqualTo(messageId);
        });
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(1));
    }

    @Test
    @Order(2)
    void poisonMessageGoesStraightToDltWithoutRetriesAndListenerStaysAlive() {
        String poison = "this is not json {{{ " + UUID.randomUUID();
        kafkaTemplate.send(BOOKING_TOPIC, EVENT_ID, poison);

        ConsumerRecord<String, String> dltRecord =
                awaitRecord(BOOKING_DLT, r -> poison.equals(r.value()), TIMEOUT);
        assertThat(exceptionInfo(dltRecord)).contains("PoisonMessageException");

        // Non-retryable: the poison record never touched a retry topic.
        assertThat(drain(BOOKING_RETRY_TOPICS, Duration.ofSeconds(3)))
                .noneMatch(r -> poison.equals(r.value()));

        // No row was claimed for the unparseable payload.
        assertThat(repository.findAll())
                .noneMatch(n -> poison.equals(n.getBody()));

        // Listener is still alive: the next valid message sails through.
        kafkaTemplate.send(BOOKING_TOPIC, EVENT_ID, """
                {"type":"BookingConfirmed","messageId":"%s","bookingId":"%s","eventId":"%s",
                 "eventName":"Friday Night Jazz","seatId":"D-2-2","userId":"%s",
                 "userEmail":"still-alive@seatsync.local","price":30.00,
                 "occurredAt":"2026-07-16T18:35:00Z"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), EVENT_ID, UUID.randomUUID()));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            List<Notification> rows = rowsFor("still-alive@seatsync.local");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        });
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(greenMail.getReceivedMessages()).hasSize(1));
    }

    // --- helpers ------------------------------------------------------------

    private List<Notification> rowsFor(String recipient) {
        return repository.findAll().stream()
                .filter(n -> recipient.equals(n.getRecipient()))
                .toList();
    }

    /** Exception FQCN + cause + message headers written by the retry-topic machinery. */
    private static String exceptionInfo(ConsumerRecord<String, String> record) {
        return stringHeader(record, KafkaHeaders.EXCEPTION_FQCN) + " "
                + stringHeader(record, KafkaHeaders.EXCEPTION_CAUSE_FQCN) + " "
                + stringHeader(record, KafkaHeaders.EXCEPTION_MESSAGE);
    }

    private static String stringHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? ""
                : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static AdminClient newAdmin() {
        return AdminClient.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    private static KafkaConsumer<String, String> newConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "retry-dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    /** Polls {@code topic} from the beginning until a record matches. */
    private static ConsumerRecord<String, String> awaitRecord(
            String topic, Predicate<ConsumerRecord<String, String>> matcher, Duration timeout) {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matcher.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record arrived on " + topic + " within " + timeout);
    }

    /** Reads everything currently on {@code topics}, stopping after {@code idle} without news. */
    private static List<ConsumerRecord<String, String>> drain(List<String> topics, Duration idle) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            consumer.subscribe(topics);
            long cap = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            long lastNews = System.nanoTime();
            while (System.nanoTime() - lastNews < idle.toNanos() && System.nanoTime() < cap) {
                var polled = consumer.poll(Duration.ofMillis(250));
                if (!polled.isEmpty()) {
                    polled.forEach(all::add);
                    lastNews = System.nanoTime();
                }
            }
        }
        return all;
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
