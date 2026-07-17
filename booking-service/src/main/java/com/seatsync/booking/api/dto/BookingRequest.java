package com.seatsync.booking.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record BookingRequest(@NotNull UUID holdId) {
}
