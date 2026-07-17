package com.seatsync.catalog.web;

import com.seatsync.catalog.service.VenueService;
import com.seatsync.catalog.web.dto.CreateVenueRequest;
import com.seatsync.catalog.web.dto.VenueResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/catalog/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping
    public List<VenueResponse> list() {
        return venueService.list();
    }

    @PostMapping
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse created = venueService.create(request);
        return ResponseEntity.created(URI.create("/api/catalog/venues/" + created.id())).body(created);
    }
}
