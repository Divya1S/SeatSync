package com.seatsync.catalog.web;

import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.security.JwtUser;
import com.seatsync.catalog.service.EventService;
import com.seatsync.catalog.web.dto.CreateEventRequest;
import com.seatsync.catalog.web.dto.EventResponse;
import com.seatsync.catalog.web.dto.PageResponse;
import com.seatsync.catalog.web.dto.SeatMapResponse;
import com.seatsync.catalog.web.dto.UpdateEventRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalog/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public PageResponse<EventResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return eventService.search(q, category, city, from, to, page, size);
    }

    @GetMapping("/mine")
    public List<EventResponse> mine(@AuthenticationPrincipal JwtUser user) {
        return eventService.myEvents(user);
    }

    @GetMapping("/{id}")
    public EventResponse get(@PathVariable UUID id, @AuthenticationPrincipal JwtUser user) {
        return eventService.getEvent(id, user);
    }

    @GetMapping("/{id}/seatmap")
    public SeatMapResponse seatMap(@PathVariable UUID id, @AuthenticationPrincipal JwtUser user) {
        return eventService.getSeatMap(id, user);
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request,
                                                @AuthenticationPrincipal JwtUser user) {
        EventResponse created = eventService.create(request, user);
        return ResponseEntity.created(URI.create("/api/catalog/events/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public EventResponse update(@PathVariable UUID id,
                                @Valid @RequestBody UpdateEventRequest request,
                                @AuthenticationPrincipal JwtUser user) {
        return eventService.update(id, request, user);
    }

    @PostMapping("/{id}/publish")
    public EventResponse publish(@PathVariable UUID id, @AuthenticationPrincipal JwtUser user) {
        return eventService.publish(id, user);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @AuthenticationPrincipal JwtUser user) {
        eventService.cancel(id, user);
        return ResponseEntity.noContent().build();
    }
}
