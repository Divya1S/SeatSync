package com.seatsync.booking.api.dto;

import java.util.List;
import java.util.UUID;

public record SeatsResponse(UUID eventId, List<SeatView> seats) {
}
