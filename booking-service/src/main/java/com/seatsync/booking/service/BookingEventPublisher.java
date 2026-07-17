package com.seatsync.booking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.config.KafkaTopicsConfig;
import com.seatsync.booking.domain.Booking;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.OutboxEvent;
import com.seatsync.booking.repo.OutboxEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the event payloads from conventions section 6.4 to the transactional
 * outbox. MUST be called INSIDE the domain transaction: the outbox row commits
 * (or rolls back) atomically with the state change, closing the dual-write gap.
 * The {@link OutboxRelay} publishes rows to Kafka asynchronously.
 *
 * <p>Every payload carries {@code messageId} = the outbox row id (a UUID unique
 * per emission) for consumer dedupe; {@code eventId} remains the catalog event
 * id and is the Kafka message key (per-event ordering).
 */
@Component
public class BookingEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public BookingEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void bookingConfirmed(Booking booking) {
        UUID messageId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "BookingConfirmed");
        payload.put("messageId", messageId.toString());
        payload.put("bookingId", str(booking.getId()));
        payload.put("eventId", str(booking.getEventId()));
        payload.put("eventName", booking.getEventName());
        payload.put("seatId", booking.getSeatId());
        payload.put("userId", str(booking.getUserId()));
        payload.put("userEmail", booking.getUserEmail());
        payload.put("price", booking.getPrice());
        payload.put("occurredAt", Instant.now().toString());
        write(messageId, KafkaTopicsConfig.TOPIC_BOOKING_CONFIRMED, booking.getEventId(), payload);
    }

    public void holdExpired(Hold hold, String eventName) {
        UUID messageId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "HoldExpired");
        payload.put("messageId", messageId.toString());
        payload.put("holdId", str(hold.getId()));
        payload.put("eventId", str(hold.getEventId()));
        payload.put("eventName", eventName);
        payload.put("seatId", hold.getSeatId());
        payload.put("userId", str(hold.getUserId()));
        payload.put("userEmail", hold.getUserEmail());
        payload.put("occurredAt", Instant.now().toString());
        write(messageId, KafkaTopicsConfig.TOPIC_HOLD_EXPIRED, hold.getEventId(), payload);
    }

    /** {@code holdId} is the system-created offer hold owned by the waitlisted user (section 6.3). */
    public void waitlistOffered(UUID eventId, String eventName, String seatId, UUID userId,
                                String userEmail, UUID holdId, Instant offerExpiresAt) {
        UUID messageId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "WaitlistOffered");
        payload.put("messageId", messageId.toString());
        payload.put("holdId", str(holdId));
        payload.put("eventId", str(eventId));
        payload.put("eventName", eventName);
        payload.put("seatId", seatId);
        payload.put("userId", str(userId));
        payload.put("userEmail", userEmail);
        payload.put("offerExpiresAt", offerExpiresAt.toString());
        payload.put("occurredAt", Instant.now().toString());
        write(messageId, KafkaTopicsConfig.TOPIC_WAITLIST_OFFERED, eventId, payload);
    }

    private void write(UUID messageId, String topic, UUID eventId, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Maps of strings/numbers cannot fail to serialize; treat as a bug.
            throw new IllegalStateException("Could not serialize outbox payload for " + topic, e);
        }
        outboxEventRepository.save(new OutboxEvent(messageId, topic, eventId.toString(), json, Instant.now()));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
