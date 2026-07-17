package com.seatsync.catalog.service;

import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.Section;
import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import com.seatsync.catalog.web.dto.VenueResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Maps entities to the wire shapes of CONVENTIONS §6.2. The seat map is DERIVED from the
 * sections at read time: rows are labeled "1".."rowCount", seats are numbered 1..seatsPerRow,
 * and the seat identity is {@code "<section>-<row>-<number>"} (e.g. "A-3-12").
 */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static VenueResponse toResponse(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity(), venue.getAddress());
    }

    public static EventResponse toResponse(Event event) {
        List<Section> sections = event.getSections();
        int totalSeats = sections.stream()
                .mapToInt(section -> section.getRowCount() * section.getSeatsPerRow())
                .sum();
        BigDecimal priceFrom = sections.stream()
                .map(Section::getPrice)
                .min(Comparator.naturalOrder())
                .map(CatalogMapper::money)
                .orElse(null);
        BigDecimal priceTo = sections.stream()
                .map(Section::getPrice)
                .max(Comparator.naturalOrder())
                .map(CatalogMapper::money)
                .orElse(null);
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getDescription(),
                event.getCategory(),
                toResponse(event.getVenue()),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getStatus(),
                event.getOrganizerId(),
                totalSeats,
                priceFrom,
                priceTo);
    }

    public static SeatMapResponse toSeatMap(Event event) {
        List<SeatMapResponse.SectionSeats> sections = new ArrayList<>();
        for (Section section : event.getSections()) {
            BigDecimal price = money(section.getPrice());
            List<SeatMapResponse.Row> rows = new ArrayList<>(section.getRowCount());
            for (int row = 1; row <= section.getRowCount(); row++) {
                String label = String.valueOf(row);
                List<SeatMapResponse.Seat> seats = new ArrayList<>(section.getSeatsPerRow());
                for (int number = 1; number <= section.getSeatsPerRow(); number++) {
                    String seatId = section.getName() + "-" + label + "-" + number;
                    seats.add(new SeatMapResponse.Seat(seatId, number, price));
                }
                rows.add(new SeatMapResponse.Row(label, seats));
            }
            sections.add(new SeatMapResponse.SectionSeats(section.getName(), section.getPriceTier(), price, rows));
        }
        return new SeatMapResponse(event.getId(), sections);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
