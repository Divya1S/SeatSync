package com.seatsync.booking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.seatsync.booking.domain.Booking;
import com.seatsync.booking.domain.BookingStatus;
import com.seatsync.booking.domain.Hold;
import com.seatsync.booking.domain.HoldStatus;
import com.seatsync.booking.domain.OutboxEvent;
import com.seatsync.booking.repo.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Producer-side wire-contract test: every payload written to the outbox must
 * validate against the repo-level JSON Schemas in contracts/events/ (the
 * contract CI gates for backward compatibility). If this test fails, either
 * the payload builder or the schema changed without the other.
 */
class EventSchemaContractTest {

    private static final Path CONTRACTS_DIR = Path.of("..", "contracts", "events");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxEventRepository outboxRepo;
    private BookingEventPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxRepo = mock(OutboxEventRepository.class);
        publisher = new BookingEventPublisher(outboxRepo, objectMapper);
    }

    @Test
    void bookingConfirmedPayloadMatchesSchema() throws Exception {
        Booking booking = new Booking(UUID.randomUUID(), "Friday Night Jazz", "A-1-2",
                UUID.randomUUID(), "attendee@seatsync.local", new BigDecimal("45.00"),
                BookingStatus.CONFIRMED, Instant.now(), UUID.randomUUID());
        ReflectionTestUtils.setField(booking, "id", UUID.randomUUID());

        publisher.bookingConfirmed(booking);

        assertValid(capturedPayload(), "booking-confirmed.schema.json");
    }

    @Test
    void holdExpiredPayloadMatchesSchema() throws Exception {
        Hold hold = new Hold(UUID.randomUUID(), UUID.randomUUID(), "B-3-7", UUID.randomUUID(),
                "attendee@seatsync.local", HoldStatus.EXPIRED, new BigDecimal("35.00"),
                Instant.now().minusSeconds(1), Instant.now().minusSeconds(301));

        publisher.holdExpired(hold, "Friday Night Jazz");

        assertValid(capturedPayload(), "hold-expired.schema.json");
    }

    @Test
    void waitlistOfferedPayloadMatchesSchema() throws Exception {
        publisher.waitlistOffered(UUID.randomUUID(), "Friday Night Jazz", "A-1-2",
                UUID.randomUUID(), "waiting@seatsync.local", UUID.randomUUID(),
                Instant.now().plusSeconds(600));

        assertValid(capturedPayload(), "waitlist-offered.schema.json");
    }

    private String capturedPayload() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(captor.capture());
        return captor.getValue().getPayload();
    }

    private void assertValid(String payloadJson, String schemaFile) throws Exception {
        Path schemaPath = CONTRACTS_DIR.resolve(schemaFile);
        assertThat(schemaPath).as("schema file must exist (repo layout: contracts/events)").exists();

        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(schemaPath), config);
        JsonNode payload = objectMapper.readTree(payloadJson);

        Set<ValidationMessage> errors = schema.validate(payload);
        assertThat(errors)
                .as("payload must satisfy %s — payload was: %s", schemaFile, payloadJson)
                .isEmpty();
    }
}
