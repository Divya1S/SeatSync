package com.seatsync.catalog.web.dto;

import com.seatsync.catalog.domain.PriceTier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SeatMapResponse(UUID eventId, List<SectionSeats> sections) {

    public record SectionSeats(String name, PriceTier priceTier, BigDecimal price, List<Row> rows) {
    }

    public record Row(String label, List<Seat> seats) {
    }

    public record Seat(String seatId, int number, BigDecimal price) {
    }
}
