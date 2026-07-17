package com.seatsync.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.notification.domain.Notification;
import com.seatsync.notification.domain.NotificationRepository;
import com.seatsync.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Core pipeline per §6.5: parse event → CLAIM the notifications row (status
 * SENDING, message_id under a UNIQUE constraint) → send via SMTP → update the
 * row to SENT/FAILED → structured log.
 *
 * <p>The claim insert commits in its own transaction (the repository call —
 * this method is deliberately NOT transactional) BEFORE any email leaves.
 * On a unique-constraint conflict (redelivery — Kafka is at-least-once via
 * the outbox) the existing row decides the outcome:
 * <ul>
 *   <li><b>SENT</b> → skip (duplicate delivery, INFO log);</li>
 *   <li><b>FAILED</b> → retry the send on this delivery, reusing the row;</li>
 *   <li><b>SENDING</b> fresher than {@link #STALE_CLAIM_AFTER} → skip
 *       (concurrent in-flight attempt);</li>
 *   <li><b>SENDING</b> older than that → crashed attempt, retry.</li>
 * </ul>
 * Legacy payloads without a {@code messageId} are processed without dedupe.
 *
 * <p>Failure contract (feeds the retry-topic machinery in
 * {@code KafkaRetryTopicConfig}): poison payloads throw
 * {@link PoisonMessageException} BEFORE any claim (straight to the DLT);
 * transient failures (mail transport, DB unavailability) mark the row FAILED
 * and re-throw so the record is redelivered via the retry topics and finally
 * parked in the DLT.
 */
@Service
public class NotificationService {

    /** §6.5: a SENDING claim older than this is treated as a crashed attempt. */
    static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ObjectMapper objectMapper;
    private final EmailComposer emailComposer;
    private final JavaMailSender mailSender;
    private final NotificationRepository repository;
    private final String fromAddress;

    public NotificationService(ObjectMapper objectMapper,
                               EmailComposer emailComposer,
                               JavaMailSender mailSender,
                               NotificationRepository repository,
                               @Value("${notification.mail.from}") String fromAddress) {
        this.objectMapper = objectMapper;
        this.emailComposer = emailComposer;
        this.mailSender = mailSender;
        this.repository = repository;
        this.fromAddress = fromAddress;
    }

    public void process(String rawPayload) {
        ComposedEmail email;
        UUID messageId;
        try {
            JsonNode event = objectMapper.readTree(rawPayload);
            messageId = messageIdOf(event);
            email = emailComposer.compose(event);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Poison notification event — routing to DLT without retries ({}): payload={}",
                    e.getMessage(), abbreviate(rawPayload));
            throw new PoisonMessageException("Unprocessable notification payload: " + e.getMessage(), e);
        }

        Notification notification = claim(messageId, email);
        if (notification == null) {
            return; // duplicate SENT delivery or concurrent in-flight claim — nothing to send
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(email.recipient());
            message.setSubject(email.subject());
            message.setText(email.body());
            mailSender.send(message);
        } catch (RuntimeException sendFailure) {
            markFailed(notification, email, sendFailure);
            // Transient (MailException & friends): the retry-topic machinery
            // redelivers via <topic>-retry and finally parks in <topic>-dlt.
            throw sendFailure;
        }

        notification.setStatus(NotificationStatus.SENT);
        try {
            repository.save(notification);
        } catch (RuntimeException e) {
            // The email IS out. Throwing would redeliver and (once the claim
            // goes stale) re-send — swallow instead; the send stays traceable
            // in the log and the row stays SENDING.
            log.warn("Failed to update notification row to SENT: id={} type={} recipient={} error={}",
                    notification.getId(), email.type(), email.recipient(), e.getMessage());
        }

        log.info("Notification sent: type={} recipient={} eventId={} seatId={} messageId={} subject=\"{}\"",
                email.type(), email.recipient(), email.eventId(), email.seatId(), messageId,
                email.subject());
    }

    /**
     * Inserts the SENDING row — the dedupe claim — in its own committed
     * transaction. On a unique-constraint conflict, applies the §6.5 claim
     * state machine against the existing row. Returns {@code null} if this
     * delivery must not send (already SENT, or another attempt is in flight).
     *
     * <p>DB failures propagate: transient ones are retried by the retry-topic
     * machinery; anything else parks the record (payload intact) in the DLT.
     */
    private Notification claim(UUID messageId, ComposedEmail email) {
        Notification notification = new Notification(messageId, email.type(), email.recipient(),
                email.subject(), email.body(), email.eventId(), email.seatId(),
                NotificationStatus.SENDING);
        try {
            return repository.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            Notification existing = messageId == null ? null
                    : repository.findByMessageId(messageId).orElse(null);
            if (existing == null) {
                // Not the messageId dedupe claim (or the row vanished) —
                // surface it; the record lands in the DLT and stays replayable.
                throw e;
            }
            return resolveConflictingClaim(existing, messageId, email);
        }
    }

    /**
     * §6.5 claim state machine for a redelivered {@code messageId}:
     * SENT → skip; FAILED → retry (reuse the row); SENDING fresher than
     * {@link #STALE_CLAIM_AFTER} → skip (concurrent); stale SENDING → retry
     * (crashed attempt).
     */
    private Notification resolveConflictingClaim(Notification existing, UUID messageId,
                                                 ComposedEmail email) {
        switch (existing.getStatus()) {
            case SENT -> {
                log.info("duplicate messageId {} skipped (already SENT): type={} recipient={} eventId={} seatId={}",
                        messageId, email.type(), email.recipient(), email.eventId(), email.seatId());
                return null;
            }
            case FAILED -> {
                log.info("retrying FAILED notification on redelivery: messageId={} type={} recipient={}",
                        messageId, email.type(), email.recipient());
                return reclaim(existing);
            }
            default -> { // SENDING
                Instant lastTouched = existing.getUpdatedAt() != null ? existing.getUpdatedAt()
                        : existing.getCreatedAt();
                if (lastTouched.isBefore(Instant.now().minus(STALE_CLAIM_AFTER))) {
                    log.warn("stale SENDING claim (last touched {}) treated as crashed attempt — retrying: "
                                    + "messageId={} type={} recipient={}",
                            lastTouched, messageId, email.type(), email.recipient());
                    return reclaim(existing);
                }
                log.info("concurrent SENDING claim fresher than {} — skipping delivery: messageId={}",
                        STALE_CLAIM_AFTER, messageId);
                return null;
            }
        }
    }

    /** Re-claims an existing row (FAILED or stale SENDING) before re-sending. */
    private Notification reclaim(Notification existing) {
        existing.setStatus(NotificationStatus.SENDING);
        return repository.saveAndFlush(existing);
    }

    private void markFailed(Notification notification, ComposedEmail email, Exception cause) {
        log.warn("Failed to send email: type={} recipient={} eventId={} seatId={} error={}",
                email.type(), email.recipient(), email.eventId(), email.seatId(), cause.getMessage());
        try {
            notification.setStatus(NotificationStatus.FAILED);
            repository.save(notification);
        } catch (RuntimeException persistFailure) {
            // Row stays SENDING; the claim goes stale after STALE_CLAIM_AFTER
            // and a later redelivery (retry topic or DLT replay) retries it.
            log.warn("Failed to record FAILED status: id={} type={} recipient={} error={}",
                    notification.getId(), email.type(), email.recipient(), persistFailure.getMessage());
        }
    }

    /**
     * §6.4: {@code messageId} is a unique UUID per event emission. Absent or
     * malformed → null → legacy path, processed without dedupe.
     */
    private static UUID messageIdOf(JsonNode event) {
        String raw = event.path("messageId").asText("");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed messageId \"{}\" — processing without dedupe", raw);
            return null;
        }
    }

    private static String abbreviate(String payload) {
        if (payload == null) {
            return "null";
        }
        return payload.length() <= 500 ? payload : payload.substring(0, 500) + "…";
    }
}
