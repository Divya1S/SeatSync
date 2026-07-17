package com.seatsync.booking.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record HoldRequest(@NotNull UUID eventId, @NotBlank String seatId) {
}
