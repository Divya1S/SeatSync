package com.seatsync.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The receiving half of the Redis WS fanout bridge (review #6): subscribed to
 * {@code seatsync:seat-events} on every instance, it parses the
 * {@code {destination, payload}} envelope and forwards the payload to the LOCAL
 * simple broker. The payload is relayed as a map, so the JSON on the STOMP
 * topic is byte-for-byte the shape clients always received.
 */
@Component
public class SeatEventRelay implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatEventRelay.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public SeatEventRelay(SimpMessagingTemplate messagingTemplate, ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonNode envelope = objectMapper.readTree(message.getBody());
            String destination = envelope.path("destination").asText(null);
            JsonNode payloadNode = envelope.get("payload");
            if (destination == null || payloadNode == null || payloadNode.isNull()) {
                log.warn("Dropping malformed seat-event envelope: {}", envelope);
                return;
            }
            Map<String, Object> payload = objectMapper.convertValue(payloadNode,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            log.warn("Failed to relay seat event to local WS broker: {}", e.getMessage());
        }
    }
}
