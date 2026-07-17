package com.seatsync.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    /**
     * Unique id of the Kafka event emission (§6.4 {@code messageId}), claimed
     * under a UNIQUE constraint before the email is sent. Null for legacy
     * messages that predate the messageId contract (no dedupe possible).
     */
    @Column(name = "message_id", unique = true)
    private UUID messageId;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false, length = 320)
    private String recipient;

    @Column(nullable = false, length = 512)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "seat_id", length = 64)
    private String seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Touched on every status transition. §6.5 staleness: a SENDING claim
     * whose {@code updatedAt} is older than 5 minutes is treated as a crashed
     * attempt and may be re-claimed by a later delivery.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Notification() {
        // JPA
    }

    public Notification(UUID messageId, String type, String recipient, String subject, String body,
                        UUID eventId, String seatId, NotificationStatus status) {
        this.id = UUID.randomUUID();
        this.messageId = messageId;
        this.type = type;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.eventId = eventId;
        this.seatId = seatId;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getType() {
        return type;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSeatId() {
        return seatId;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
