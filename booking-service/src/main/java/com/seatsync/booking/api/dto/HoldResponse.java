package com.seatsync.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID holdId, UUID eventId, String seatId, UUID userId,
                           Instant expiresAt, String status) {
}
