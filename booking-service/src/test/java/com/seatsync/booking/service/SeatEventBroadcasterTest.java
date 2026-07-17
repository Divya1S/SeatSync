package com.seatsync.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

/**
 * The publishing half of the Redis WS fanout bridge (review #6): broadcasts go
 * to the Redis channel as {destination, payload} JSON — never to the local
 * broker directly — and a Redis failure never propagates to the caller.
 */
@ExtendWith(MockitoExtension.class)
class SeatEventBroadcasterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SeatEventBroadcaster broadcaster;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        broadcaster = new SeatEventBroadcaster(redisTemplate, objectMapper);
    }

    @Test
    void publishesEnvelopeWithDestinationAndUnchangedPayloadShapeToTheFanoutChannel() throws Exception {
        broadcaster.seatHeld(eventId, "A-1-7");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).convertAndSend(eq("seatsync:seat-events"), messageCaptor.capture());

        JsonNode envelope = objectMapper.readTree(messageCaptor.getValue());
        assertThat(envelope.get("destination").asText())
                .isEqualTo("/topic/events/" + eventId + "/seats");
        JsonNode payload = envelope.get("payload");
        assertThat(payload.get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(payload.get("seatId").asText()).isEqualTo("A-1-7");
        assertThat(payload.get("status").asText()).isEqualTo("HELD");
        assertThat(payload.get("at").asText()).isNotBlank();
    }

    @Test
    void statusesMapToBookedAndAvailable() throws Exception {
        broadcaster.seatBooked(eventId, "A-1-1");
        broadcaster.seatAvailable(eventId, "A-1-2");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate, org.mockito.Mockito.times(2))
                .convertAndSend(eq("seatsync:seat-events"), messageCaptor.capture());

        JsonNode booked = objectMapper.readTree(messageCaptor.getAllValues().get(0)).get("payload");
        assertThat(booked.get("status").asText()).isEqualTo("BOOKED");
        JsonNode available = objectMapper.readTree(messageCaptor.getAllValues().get(1)).get("payload");
        assertThat(available.get("status").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void redisFailureNeverPropagatesToTheCaller() {
        doThrow(new RuntimeException("redis down")).when(redisTemplate)
                .convertAndSend(anyString(), anyString());

        assertThatCode(() -> broadcaster.seatBooked(eventId, "A-1-1")).doesNotThrowAnyException();
    }
}
