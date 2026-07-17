package com.seatsync.booking;

import com.seatsync.booking.support.AbstractBookingIT;
import com.seatsync.booking.support.TestTokens;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

/**
 * The delivery half of the transactional outbox (review #1): a booking parks a
 * BookingConfirmed row in the outbox inside the confirm transaction; the
 * OutboxRelay claims it, publishes it to real Kafka with an ack wait, marks
 * published_at, and the consumed message carries {@code messageId} (= the
 * outbox row id) for consumer dedupe.
 */
class OutboxRelayIT extends AbstractBookingIT {

    @Test
    void pendingOutboxRowIsPublishedMarkedAndConsumedMessageCarriesMessageId() {
        UUID eventId = UUID.randomUUID();
        when(catalogGateway.getEvent(eventId)).thenReturn(publishedEvent(eventId, "Relay Fest"));
        when(catalogGateway.getSeatMap(eventId)).thenReturn(seatMapOf(eventId, 2, new BigDecimal("42.00")));

        String token = TestTokens.attendee(UUID.randomUUID(), "relay@test.io");
        ResponseEntity<Map<String, Object>> hold = postJson("/api/holds",
                Map.of("eventId", eventId.toString(), "seatId", "A-1-1"), token);
        assertThat(hold.getStatusCode().value()).isEqualTo(201);
        ResponseEntity<Map<String, Object>> confirm = postJson("/api/bookings",
                Map.of("holdId", String.valueOf(hold.getBody().get("holdId"))), token);
        assertThat(confirm.getStatusCode().value()).isEqualTo(201);

        // The relay (fixedDelay 2s) publishes the parked row and marks it.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select id, published_at, attempts from outbox_events "
                            + "where topic = 'seatsync.booking.confirmed' and message_key = ?",
                    eventId.toString());
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("published_at"))
                    .as("relay must mark the row published").isNotNull();
        });
        UUID messageId = (UUID) jdbc.queryForList(
                "select id, published_at from outbox_events "
                        + "where topic = 'seatsync.booking.confirmed' and message_key = ?",
                eventId.toString()).get(0).get("id");

        // The message really reached the broker and carries messageId = row id.
        String value = consumeUntilFound("seatsync.booking.confirmed", eventId.toString(), messageId.toString());
        assertThat(value).as("published message must be consumable from Kafka").isNotNull();
        assertThat(value)
                .contains("\"type\":\"BookingConfirmed\"")
                .contains("\"messageId\":\"" + messageId + "\"")
                .contains("\"eventId\":\"" + eventId + "\"")
                .contains("\"seatId\":\"A-1-1\"");

        // After relay success nothing is left unpublished for this event.
        Integer unpublished = jdbc.queryForObject(
                "select count(*) from outbox_events where message_key = ? and published_at is null",
                Integer.class, eventId.toString());
        assertThat(unpublished).isZero();
    }

    /** Polls the topic until a record with the given key containing the marker appears (30s cap). */
    private static String consumeUntilFound(String topic, String key, String marker) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key()) && record.value() != null && record.value().contains(marker)) {
                        return record.value();
                    }
                }
            }
        }
        return null;
    }
}
