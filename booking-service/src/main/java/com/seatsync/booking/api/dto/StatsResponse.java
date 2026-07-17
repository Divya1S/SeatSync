package com.seatsync.booking.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StatsResponse(UUID eventId, long totalSeats, long available, long held,
                            long booked, BigDecimal revenue) {
}
