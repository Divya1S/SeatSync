package com.seatsync.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_name")
    private String eventName;

    @Column(name = "seat_id", nullable = false, length = 64)
    private String seatId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    /**
     * The hold this booking was confirmed from — UNIQUE in the database: one hold
     * produces at most one booking, making holdId the natural idempotency key for
     * POST /api/bookings (conventions section 6.3). Nullable only for legacy rows.
     */
    @Column(name = "hold_id", unique = true)
    private UUID holdId;

    protected Booking() {
    }

    public Booking(UUID eventId, String eventName, String seatId, UUID userId, String userEmail,
                   BigDecimal price, BookingStatus status, Instant confirmedAt, UUID holdId) {
        this.eventId = eventId;
        this.eventName = eventName;
        this.seatId = seatId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.price = price;
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.holdId = holdId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventName() {
        return eventName;
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

    public BigDecimal getPrice() {
        return price;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public UUID getHoldId() {
        return holdId;
    }
}
