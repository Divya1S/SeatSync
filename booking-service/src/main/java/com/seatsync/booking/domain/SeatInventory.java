package com.seatsync.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One physical seat of one event. The {@link Version @Version} column is the FINAL
 * concurrency guard for the AVAILABLE -> BOOKED transition: even if Redis-based hold
 * exclusivity were ever bypassed, the optimistic lock guarantees a single winner.
 */
@Entity
@Table(name = "seat_inventory",
        uniqueConstraints = @UniqueConstraint(name = "uq_seat_inventory_event_seat", columnNames = {"event_id", "seat_id"}))
public class SeatInventory {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, length = 64)
    private String seatId;

    @Column(name = "section", nullable = false, length = 64)
    private String section;

    @Column(name = "row_label", nullable = false, length = 16)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SeatStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected SeatInventory() {
    }

    public SeatInventory(UUID eventId, String seatId, String section, String rowLabel,
                         int seatNumber, BigDecimal price, SeatStatus status) {
        this.eventId = eventId;
        this.seatId = seatId;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.price = price;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSeatId() {
        return seatId;
    }

    public String getSection() {
        return section;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public Long getVersion() {
        return version;
    }
}
