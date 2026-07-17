package com.seatsync.booking.api.dto;

import java.time.Instant;
import java.util.UUID;

public record HoldSummary(UUID holdId, UUID eventId, String seatId, Instant expiresAt) {
}
