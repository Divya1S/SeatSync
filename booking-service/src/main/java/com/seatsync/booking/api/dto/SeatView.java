package com.seatsync.booking.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

public record SeatView(String seatId, String status, boolean mine,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Instant holdExpiresAt) {
}
