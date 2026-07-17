package com.seatsync.booking.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Catalog seat map, per conventions section 6.2.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SeatMapDto(UUID eventId, List<Section> sections) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String name, String priceTier, BigDecimal price, List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String label, List<Seat> seats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seat(String seatId, int number, BigDecimal price) {
    }
}
