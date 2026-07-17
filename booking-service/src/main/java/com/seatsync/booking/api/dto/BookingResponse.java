package com.seatsync.booking.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BookingResponse(UUID bookingId, UUID eventId, String eventName, String seatId,
                              BigDecimal price, String status, Instant confirmedAt) {
}
