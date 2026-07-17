package com.seatsync.catalog.seed;

import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;
import com.seatsync.catalog.domain.PriceTier;
import com.seatsync.catalog.domain.Section;
import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.repository.EventRepository;
import com.seatsync.catalog.repository.VenueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent demo seed (CONVENTIONS §6.2): runs only when the venues table is empty.
 * The demo organizer UUID is fixed by convention — auth-service seeds
 * organizer@seatsync.local with exactly this id.
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    static final UUID DEMO_ORGANIZER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final VenueRepository venues;
    private final EventRepository events;

    public DataSeeder(VenueRepository venues, EventRepository events) {
        this.venues = venues;
        this.events = events;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (venues.count() > 0) {
            log.info("Catalog seed skipped: venues already present");
            return;
        }

        Venue blueNote = venues.save(new Venue("The Blue Note Hall", "Austin",
                "1204 Congress Ave, Austin, TX 78701"));
        Venue riverside = venues.save(new Venue("Riverside Pavilion", "Austin",
                "35 East Riverside Dr, Austin, TX 78704"));

        LocalDate nextFriday = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
        Instant jazzStart = nextFriday.atTime(LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC);

        seedEvent("Friday Night Jazz",
                "An intimate evening of live jazz standards, bebop and improvisation with the "
                        + "Austin Downtown Quartet.",
                EventCategory.CONCERT, blueNote,
                jazzStart, jazzStart.plus(Duration.ofHours(3)),
                List.of(
                        new Section("A", PriceTier.STANDARD, new BigDecimal("45.00"), 5, 10),
                        new Section("B", PriceTier.PREMIUM, new BigDecimal("75.00"), 3, 8),
                        new Section("VIP", PriceTier.VIP, new BigDecimal("120.00"), 1, 6)));

        Instant workshopStart = nextFriday.plusDays(5).atTime(LocalTime.of(9, 0)).toInstant(ZoneOffset.UTC);
        seedEvent("Hands-On Microservices Workshop",
                "A full-day, laptop-required workshop on event-driven microservices with Java, "
                        + "Kafka and Redis. Lunch included.",
                EventCategory.WORKSHOP, riverside,
                workshopStart, workshopStart.plus(Duration.ofHours(8)),
                List.of(
                        new Section("A", PriceTier.STANDARD, new BigDecimal("25.00"), 4, 8),
                        new Section("B", PriceTier.PREMIUM, new BigDecimal("40.00"), 2, 6)));

        Instant derbyStart = nextFriday.plusDays(12).atTime(LocalTime.of(18, 30)).toInstant(ZoneOffset.UTC);
        seedEvent("Austin River Derby",
                "Flat-track roller derby double-header on the riverside rink. Fast, loud and "
                        + "family friendly.",
                EventCategory.SPORTS, riverside,
                derbyStart, derbyStart.plus(Duration.ofHours(3)),
                List.of(
                        new Section("A", PriceTier.STANDARD, new BigDecimal("30.00"), 5, 10),
                        new Section("B", PriceTier.PREMIUM, new BigDecimal("55.00"), 3, 8),
                        new Section("VIP", PriceTier.VIP, new BigDecimal("90.00"), 1, 6)));

        Instant fairStart = nextFriday.plusDays(19).atTime(LocalTime.of(10, 0)).toInstant(ZoneOffset.UTC);
        seedEvent("Campus Innovation Fair",
                "Student startups, research demos and recruiter booths from across the campus. "
                        + "Open to all students and alumni.",
                EventCategory.CAMPUS, blueNote,
                fairStart, fairStart.plus(Duration.ofHours(7)),
                List.of(
                        new Section("A", PriceTier.STANDARD, new BigDecimal("10.00"), 5, 10),
                        new Section("B", PriceTier.PREMIUM, new BigDecimal("18.00"), 2, 8)));

        log.info("Catalog seed complete: 2 venues, 4 published demo events (organizer {})", DEMO_ORGANIZER_ID);
    }

    private void seedEvent(String name, String description, EventCategory category, Venue venue,
                           Instant startsAt, Instant endsAt, List<Section> sections) {
        Event event = new Event(name, description, category, venue, startsAt, endsAt,
                EventStatus.PUBLISHED, DEMO_ORGANIZER_ID);
        sections.forEach(event::addSection);
        events.save(event);
    }
}
