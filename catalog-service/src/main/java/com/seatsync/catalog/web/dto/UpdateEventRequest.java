package com.seatsync.catalog.web.dto;

import com.seatsync.catalog.domain.EventCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only update: the seat map (sections) cannot be changed via PUT.
 */
public record UpdateEventRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull EventCategory category,
        @NotNull UUID venueId,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt) {
}
