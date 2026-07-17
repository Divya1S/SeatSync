package com.seatsync.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A temporary claim on a seat. The id is assigned up front (it is also embedded in the
 * Redis hold value), so the entity implements {@link Persistable} for clean inserts.
 */
@Entity
@Table(name = "holds")
public class Hold implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, length = 64)
    private String seatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    protected Hold() {
    }

    public Hold(UUID id, UUID eventId, String seatId, UUID userId, String userEmail,
                HoldStatus status, BigDecimal price, Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.eventId = eventId;
        this.seatId = seatId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.status = status;
        this.price = price;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.isNew = true;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSeatId() {
        return seatId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public void setStatus(HoldStatus status) {
        this.status = status;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
