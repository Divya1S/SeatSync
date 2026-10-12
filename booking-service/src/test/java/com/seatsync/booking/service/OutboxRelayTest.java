package com.seatsync.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.domain.OutboxEvent;
import com.seatsync.booking.repo.OutboxEventRepository;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Relay cycle semantics: acked sends are marked published; a broker outage ends
 * the cycle after the first timeout but counts the attempt on every claimed row
 * (so the retry counters keep moving); a single unprocessable row is skipped
 * without blocking the rows behind it.
 */
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, new ObjectMapper());
    }

    @Test
    void ackedSendsAreMarkedPublished() {
        OutboxEvent first = event("{\"type\":\"BookingConfirmed\"}");
        OutboxEvent second = event("{\"type\":\"HoldExpired\"}");
        when(outboxEventRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        relay.relay();

        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
        assertThat(first.getAttempts()).isZero();
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), any());
    }

    @Test
    void brokerOutageEndsTheCycleAfterOneSendButCountsAnAttemptOnEveryClaimedRow() {
        List<OutboxEvent> batch = List.of(event("{\"a\":1}"), event("{\"b\":2}"), event("{\"c\":3}"));
        when(outboxEventRepository.claimUnpublishedBatch(anyInt())).thenReturn(batch);
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new TimeoutException("Topic seatsync.booking.confirmed not present in metadata after 2000 ms.")));

        relay.relay();

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
        assertThat(batch).allSatisfy(event -> {
            assertThat(event.getPublishedAt()).isNull();
            assertThat(event.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    void unprocessablePayloadIsSkippedWithoutStoppingTheCycle() {
        OutboxEvent poison = event("this is not json");
        OutboxEvent healthy = event("{\"ok\":true}");
        when(outboxEventRepository.claimUnpublishedBatch(anyInt())).thenReturn(List.of(poison, healthy));
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        relay.relay();

        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(healthy.getPublishedAt()).isNotNull();
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
    }

    @Test
    void brokerUnavailabilityIsRecognisedThroughWrappedCauses() {
        assertThat(OutboxRelay.brokerUnavailable(new ExecutionException(new TimeoutException("metadata")))).isTrue();
        // producer.send() throws the Kafka TimeoutException synchronously after max.block.ms
        assertThat(OutboxRelay.brokerUnavailable(new TimeoutException("Topic x not present in metadata after 2000 ms."))).isTrue();
        assertThat(OutboxRelay.brokerUnavailable(new java.util.concurrent.TimeoutException("ack"))).isTrue();
        assertThat(OutboxRelay.brokerUnavailable(new IllegalArgumentException("record too large"))).isFalse();
    }

    private static OutboxEvent event(String payload) {
        return new OutboxEvent(UUID.randomUUID(), "seatsync.booking.confirmed", UUID.randomUUID().toString(),
                payload, Instant.now());
    }
}
