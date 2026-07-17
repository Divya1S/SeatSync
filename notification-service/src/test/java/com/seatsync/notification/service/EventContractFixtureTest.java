package com.seatsync.notification.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumer-side wire-contract test: every known-good example payload in
 * contracts/events/examples (the same files the CI `contracts` job validates
 * against the JSON Schemas) must be fully processable by this consumer's
 * composer. If a schema/example evolves in a way this consumer can't handle,
 * this fails before anything reaches a broker.
 */
class EventContractFixtureTest {

    private static final Path EXAMPLES_DIR = Path.of("..", "contracts", "events", "examples");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailComposer composer = new EmailComposer();

    @ParameterizedTest
    @ValueSource(strings = {
            "booking-confirmed.example.json",
            "hold-expired.example.json",
            "waitlist-offered.example.json"
    })
    void everyContractExampleIsProcessable(String exampleFile) throws Exception {
        Path path = EXAMPLES_DIR.resolve(exampleFile);
        assertThat(path).as("contract example must exist (repo layout: contracts/events/examples)").exists();

        JsonNode event = objectMapper.readTree(Files.readString(path));
        ComposedEmail email = composer.compose(event);

        assertThat(email.recipient()).isEqualTo(event.get("userEmail").asText());
        assertThat(email.subject()).contains(event.get("eventName").asText());
        assertThat(email.body()).contains(event.get("seatId").asText());
    }

    @Test
    void examplesCarryTheDedupeKey() throws Exception {
        for (String f : new String[]{"booking-confirmed.example.json",
                "hold-expired.example.json", "waitlist-offered.example.json"}) {
            JsonNode event = objectMapper.readTree(Files.readString(EXAMPLES_DIR.resolve(f)));
            assertThat(event.hasNonNull("messageId")).as("%s must carry messageId", f).isTrue();
        }
    }
}
