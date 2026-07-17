package com.seatsync.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The subscribing half of the Redis WS fanout bridge (review #6): every
 * instance relays channel messages to its LOCAL simple broker, preserving the
 * STOMP message body shape.
 */
@ExtendWith(MockitoExtension.class)
class SeatEventRelayTest {

    private static final byte[] CHANNEL = "seatsync:seat-events".getBytes(StandardCharsets.UTF_8);

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private SeatEventRelay relay;

    @BeforeEach
    void setUp() {
        relay = new SeatEventRelay(messagingTemplate, new ObjectMapper());
    }

    @Test
    void relaysParsedPayloadToTheLocalBrokerAtTheEnvelopeDestination() {
        UUID eventId = UUID.randomUUID();
        String json = """
                {"destination":"/topic/events/%s/seats",
                 "payload":{"eventId":"%s","seatId":"A-1-7","status":"HELD","at":"2026-07-16T18:00:00Z"}}
                """.formatted(eventId, eventId);

        relay.onMessage(new DefaultMessage(CHANNEL, json.getBytes(StandardCharsets.UTF_8)), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/events/" + eventId + "/seats"), payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .containsEntry("eventId", eventId.toString())
                .containsEntry("seatId", "A-1-7")
                .containsEntry("status", "HELD")
                .containsEntry("at", "2026-07-16T18:00:00Z");
    }

    @Test
    void malformedEnvelopeIsDroppedWithoutThrowing() {
        assertThatCode(() -> {
            relay.onMessage(new DefaultMessage(CHANNEL, "not json".getBytes(StandardCharsets.UTF_8)), null);
            relay.onMessage(new DefaultMessage(CHANNEL, "{\"payload\":{}}".getBytes(StandardCharsets.UTF_8)), null);
            relay.onMessage(new DefaultMessage(CHANNEL,
                    "{\"destination\":\"/topic/x\"}".getBytes(StandardCharsets.UTF_8)), null);
        }).doesNotThrowAnyException();

        verify(messagingTemplate, never()).convertAndSend(anyString(), (Object) any());
    }
}
