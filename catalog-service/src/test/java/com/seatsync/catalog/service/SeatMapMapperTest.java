package com.seatsync.catalog.service;

import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;
import com.seatsync.catalog.domain.PriceTier;
import com.seatsync.catalog.domain.Section;
import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The seat map is derived from sections at read time: rows are labeled "1".."rowCount" and
 * seat ids follow the shared "<section>-<row>-<number>" format (CONVENTIONS §6).
 */
class SeatMapMapperTest {

    private final UUID eventId = UUID.randomUUID();

    @Test
    void expandsSectionsIntoRowsAndSeatIds() {
        Event event = event(
                new Section("A", PriceTier.STANDARD, new BigDecimal("49.00"), 2, 3),
                new Section("VIP", PriceTier.VIP, new BigDecimal("120.00"), 1, 2));

        SeatMapResponse seatMap = CatalogMapper.toSeatMap(event);

        assertEquals(eventId, seatMap.eventId());
        assertEquals(2, seatMap.sections().size());

        SeatMapResponse.SectionSeats sectionA = seatMap.sections().get(0);
        assertEquals("A", sectionA.name());
        assertEquals(PriceTier.STANDARD, sectionA.priceTier());
        assertEquals(new BigDecimal("49.00"), sectionA.price());
        assertEquals(2, sectionA.rows().size());
        assertEquals(List.of("1", "2"), sectionA.rows().stream().map(SeatMapResponse.Row::label).toList());

        SeatMapResponse.Row firstRow = sectionA.rows().get(0);
        assertEquals(3, firstRow.seats().size());
        assertEquals(List.of("A-1-1", "A-1-2", "A-1-3"),
                firstRow.seats().stream().map(SeatMapResponse.Seat::seatId).toList());
        assertEquals(List.of(1, 2, 3),
                firstRow.seats().stream().map(SeatMapResponse.Seat::number).toList());
        assertEquals(new BigDecimal("49.00"), firstRow.seats().get(0).price());
        assertEquals("A-2-3", sectionA.rows().get(1).seats().get(2).seatId());

        SeatMapResponse.SectionSeats vip = seatMap.sections().get(1);
        assertEquals(1, vip.rows().size());
        assertEquals("VIP-1-2", vip.rows().get(0).seats().get(1).seatId());
        assertEquals(new BigDecimal("120.00"), vip.rows().get(0).seats().get(1).price());
    }

    @Test
    void derivesTotalSeatsAndPriceRangeOnEvent() {
        Event event = event(
                new Section("A", PriceTier.STANDARD, new BigDecimal("49.5"), 2, 3),
                new Section("VIP", PriceTier.VIP, new BigDecimal("120"), 1, 2));

        EventResponse response = CatalogMapper.toResponse(event);

        assertEquals(8, response.totalSeats());
        assertEquals(new BigDecimal("49.50"), response.priceFrom());
        assertEquals(new BigDecimal("120.00"), response.priceTo());
        assertEquals(EventCategory.CONCERT, response.category());
        assertEquals("The Blue Note Hall", response.venue().name());
        assertEquals("Austin", response.venue().city());
    }

    @Test
    void eventWithoutSectionsHasZeroSeatsAndNoPriceRange() {
        Event event = event();

        EventResponse response = CatalogMapper.toResponse(event);

        assertEquals(0, response.totalSeats());
        assertNull(response.priceFrom());
        assertNull(response.priceTo());
    }

    private Event event(Section... sections) {
        Venue venue = new Venue("The Blue Note Hall", "Austin", "1204 Congress Ave");
        venue.setId(UUID.randomUUID());
        Event event = new Event("Friday Night Jazz", "Live jazz.", EventCategory.CONCERT, venue,
                Instant.parse("2026-07-24T20:00:00Z"), Instant.parse("2026-07-24T23:00:00Z"),
                EventStatus.PUBLISHED, UUID.randomUUID());
        event.setId(eventId);
        for (Section section : sections) {
            event.addSection(section);
        }
        return event;
    }
}
