package com.seatsync.catalog.service;

import com.seatsync.catalog.cache.CatalogCache;
import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;
import com.seatsync.catalog.domain.Section;
import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.error.BadRequestException;
import com.seatsync.catalog.error.ConflictException;
import com.seatsync.catalog.error.ForbiddenException;
import com.seatsync.catalog.error.NotFoundException;
import com.seatsync.catalog.repository.EventRepository;
import com.seatsync.catalog.repository.EventSpecifications;
import com.seatsync.catalog.repository.VenueRepository;
import com.seatsync.catalog.security.JwtUser;
import com.seatsync.catalog.web.dto.CreateEventRequest;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.PageResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import com.seatsync.catalog.web.dto.SectionRequest;
import com.seatsync.catalog.web.dto.UpdateEventRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class EventService {

    private static final int MAX_PAGE_SIZE = 100;

    private final EventRepository events;
    private final VenueRepository venues;
    private final CatalogCache cache;

    public EventService(EventRepository events, VenueRepository venues, CatalogCache cache) {
        this.events = events;
        this.venues = venues;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> search(String q, EventCategory category, String city,
                                              Instant from, Instant to, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<Event> result = events.findAll(
                EventSpecifications.publicSearch(q, category, city, from, to),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "startsAt")));
        return new PageResponse<>(
                result.getContent().stream().map(CatalogMapper::toResponse).toList(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.getNumber(),
                result.getSize());
    }

    @Transactional(readOnly = true)
    public List<EventResponse> myEvents(JwtUser organizer) {
        return events.findByOrganizerIdOrderByStartsAtAsc(organizer.id()).stream()
                .map(CatalogMapper::toResponse)
                .toList();
    }

    /**
     * Public read with cache-aside: only the PUBLISHED view is cached. Admins bypass the
     * cache (they may see any status); owners fall through to the database for their own
     * DRAFT/CANCELLED events. Everyone else gets 404 for non-published events.
     */
    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID id, JwtUser viewer) {
        boolean admin = isAdmin(viewer);
        if (!admin) {
            Optional<EventResponse> cached = cache.getEvent(id);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Event event = loadWithDetails(id);
        EventResponse response = CatalogMapper.toResponse(event);
        if (admin || isOwner(event, viewer)) {
            return response;
        }
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new NotFoundException("Event " + id + " not found");
        }
        cache.putEvent(id, response);
        return response;
    }

    /** Same visibility and cache-aside rules as {@link #getEvent}. */
    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(UUID id, JwtUser viewer) {
        boolean admin = isAdmin(viewer);
        if (!admin) {
            Optional<SeatMapResponse> cached = cache.getSeatMap(id);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        Event event = loadWithDetails(id);
        SeatMapResponse response = CatalogMapper.toSeatMap(event);
        if (admin || isOwner(event, viewer)) {
            return response;
        }
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new NotFoundException("Event " + id + " not found");
        }
        cache.putSeatMap(id, response);
        return response;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request, JwtUser organizer) {
        validateTimes(request.startsAt(), request.endsAt());
        validateUniqueSectionNames(request.sections());
        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> new NotFoundException("Venue " + request.venueId() + " not found"));

        Event event = new Event(request.name(), request.description(), request.category(), venue,
                request.startsAt(), request.endsAt(), EventStatus.DRAFT, organizer.id());
        for (SectionRequest section : request.sections()) {
            event.addSection(new Section(section.name(), section.priceTier(),
                    section.price().setScale(2, RoundingMode.HALF_UP),
                    section.rowCount(), section.seatsPerRow()));
        }
        Event saved = events.save(event);
        cache.evict(saved.getId());
        return CatalogMapper.toResponse(saved);
    }

    @Transactional
    public EventResponse update(UUID id, UpdateEventRequest request, JwtUser caller) {
        Event event = loadOwned(id, caller);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("Event " + id + " is cancelled and cannot be modified");
        }
        validateTimes(request.startsAt(), request.endsAt());
        Venue venue = venues.findById(request.venueId())
                .orElseThrow(() -> new NotFoundException("Venue " + request.venueId() + " not found"));

        event.setName(request.name());
        event.setDescription(request.description());
        event.setCategory(request.category());
        event.setVenue(venue);
        event.setStartsAt(request.startsAt());
        event.setEndsAt(request.endsAt());
        Event saved = events.save(event);
        cache.evict(id);
        return CatalogMapper.toResponse(saved);
    }

    @Transactional
    public EventResponse publish(UUID id, JwtUser caller) {
        Event event = loadOwned(id, caller);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("Event " + id + " is cancelled and cannot be published");
        }
        event.setStatus(EventStatus.PUBLISHED);
        Event saved = events.save(event);
        cache.evict(id);
        return CatalogMapper.toResponse(saved);
    }

    /** Soft delete: the event is marked CANCELLED, never removed. */
    @Transactional
    public void cancel(UUID id, JwtUser caller) {
        Event event = loadOwned(id, caller);
        event.setStatus(EventStatus.CANCELLED);
        events.save(event);
        cache.evict(id);
    }

    private Event loadWithDetails(UUID id) {
        return events.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
    }

    private Event loadOwned(UUID id, JwtUser caller) {
        Event event = loadWithDetails(id);
        if (!isAdmin(caller) && !isOwner(event, caller)) {
            throw new ForbiddenException("You do not own this event");
        }
        return event;
    }

    private static boolean isAdmin(JwtUser user) {
        return user != null && user.hasRole("ADMIN");
    }

    private static boolean isOwner(Event event, JwtUser user) {
        return user != null && event.getOrganizerId().equals(user.id());
    }

    private static void validateTimes(Instant startsAt, Instant endsAt) {
        if (!startsAt.isBefore(endsAt)) {
            throw new BadRequestException("startsAt must be before endsAt");
        }
    }

    private static void validateUniqueSectionNames(List<SectionRequest> sections) {
        Set<String> names = new HashSet<>();
        for (SectionRequest section : sections) {
            if (!names.add(section.name())) {
                throw new BadRequestException("Duplicate section name: " + section.name());
            }
        }
    }
}
