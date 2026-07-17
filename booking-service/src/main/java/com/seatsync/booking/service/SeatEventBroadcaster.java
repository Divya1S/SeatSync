package com.seatsync.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.domain.SeatStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes seat state changes to the Redis fanout channel
 * {@code seatsync:seat-events} as {@code {destination, payload}} JSON
 * (review #6). Every instance's {@link SeatEventRelay} subscriber relays the
 * message to its LOCAL simple broker, so under N replicas each connected client
 * receives exactly one copy regardless of which instance produced the change.
 * Producers never send to the local broker directly.
 *
 * <p>Best effort: a Redis/WebSocket failure never fails the originating request.
 */
@Component
public class SeatEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatEventBroadcaster.class);

    /** Redis pub/sub channel for cross-instance WS fanout (conventions section 6.3). */
    public static final String CHANNEL = "seatsync:seat-events";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SeatEventBroadcaster(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void seatHeld(UUID eventId, String seatId) {
        broadcast(eventId, seatId, "HELD");
    }

    public void seatBooked(UUID eventId, String seatId) {
        broadcast(eventId, seatId, SeatStatus.BOOKED.name());
    }

    public void seatAvailable(UUID eventId, String seatId) {
        broadcast(eventId, seatId, SeatStatus.AVAILABLE.name());
    }

    private void broadcast(UUID eventId, String seatId, String status) {
        try {
            String destination = "/topic/events/" + eventId + "/seats";
            SeatMessage payload = new SeatMessage(eventId.toString(), seatId, status, Instant.now().toString());
            String message = objectMapper.writeValueAsString(new Envelope(destination, payload));
            redisTemplate.convertAndSend(CHANNEL, message);
        } catch (Exception e) {
            log.warn("Failed to broadcast seat state {} for event {} seat {}: {}",
                    status, eventId, seatId, e.getMessage());
        }
    }

    /** The Redis channel envelope: which STOMP destination, and what to send there. */
    public record Envelope(String destination, SeatMessage payload) {
    }

    /** The STOMP message body — shape unchanged from the pre-bridge broadcasts. */
    public record SeatMessage(String eventId, String seatId, String status, String at) {
    }
}
