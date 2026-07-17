package com.seatsync.catalog.web.dto;

import com.seatsync.catalog.domain.EventCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CreateEventRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull EventCategory category,
        @NotNull UUID venueId,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt,
        @NotEmpty List<@Valid SectionRequest> sections) {
}
