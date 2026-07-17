package com.seatsync.catalog.web.dto;

import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String description,
        EventCategory category,
        VenueResponse venue,
        Instant startsAt,
        Instant endsAt,
        EventStatus status,
        UUID organizerId,
        int totalSeats,
        BigDecimal priceFrom,
        BigDecimal priceTo) {
}
