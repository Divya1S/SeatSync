package com.seatsync.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.domain.OutboxEvent;
import com.seatsync.booking.repo.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox rows to Kafka (conventions section 6.3). Each cycle claims a
 * batch of unpublished rows with {@code FOR UPDATE SKIP LOCKED} (safe under N
 * replicas — the claim IS the transaction, no lease to expire), sends each row
 * with an ack wait, marks {@code published_at} on success and increments
 * {@code attempts} on failure, leaving the row for the next cycle. Delivery is
 * at-least-once; consumers dedupe on the payload's {@code messageId}.
 *
 * <p>The HTTP request path never touches Kafka: a broker outage delays events,
 * it can no longer lose them.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    static final int BATCH_SIZE = 100;
    static final long SEND_TIMEOUT_SECONDS = 5;
    static final Duration PUBLISHED_RETENTION = Duration.ofDays(7);
    private static final Duration PURGE_INTERVAL = Duration.ofHours(1);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private volatile Instant lastPurgeAt = Instant.EPOCH;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, Object> kafkaTemplate,
                       ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 2_000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxEventRepository.claimUnpublishedBatch(BATCH_SIZE);
        for (OutboxEvent event : batch) {
            try {
                Map<String, Object> payload = objectMapper.readValue(event.getPayload(),
                        new TypeReference<Map<String, Object>>() {
                        });
                // The payload is re-serialized by the producer's JsonSerializer,
                // reproducing the exact JSON written by the domain transaction.
                kafkaTemplate.send(event.getTopic(), event.getMessageKey(), payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                event.setPublishedAt(Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(event, e);
                break;
            } catch (Exception e) {
                recordFailure(event, e);
            }
        }
        // Claimed entities are managed; published_at/attempts flush at commit.
        purgeOldPublished();
    }

    private void recordFailure(OutboxEvent event, Exception e) {
        event.setAttempts(event.getAttempts() + 1);
        if (event.getAttempts() % 10 == 0) {
            log.warn("Outbox event {} (topic {}) still unpublished after {} attempts: {}",
                    event.getId(), event.getTopic(), event.getAttempts(), e.getMessage());
        } else {
            log.debug("Outbox event {} (topic {}) publish attempt {} failed: {}",
                    event.getId(), event.getTopic(), event.getAttempts(), e.getMessage());
        }
    }

    /** Piggybacked purge: drop rows published more than 7 days ago, at most hourly. */
    private void purgeOldPublished() {
        Instant now = Instant.now();
        if (Duration.between(lastPurgeAt, now).compareTo(PURGE_INTERVAL) < 0) {
            return;
        }
        lastPurgeAt = now;
        int purged = outboxEventRepository.deletePublishedBefore(now.minus(PUBLISHED_RETENTION));
        if (purged > 0) {
            log.info("Purged {} outbox events published more than {} days ago",
                    purged, PUBLISHED_RETENTION.toDays());
        }
    }
}
