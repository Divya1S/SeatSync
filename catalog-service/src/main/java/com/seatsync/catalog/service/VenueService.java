package com.seatsync.catalog.service;

import com.seatsync.catalog.domain.Venue;
import com.seatsync.catalog.repository.VenueRepository;
import com.seatsync.catalog.web.dto.CreateVenueRequest;
import com.seatsync.catalog.web.dto.VenueResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VenueService {

    private final VenueRepository venues;

    public VenueService(VenueRepository venues) {
        this.venues = venues;
    }

    @Transactional(readOnly = true)
    public List<VenueResponse> list() {
        return venues.findAllByOrderByNameAsc().stream()
                .map(CatalogMapper::toResponse)
                .toList();
    }

    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = venues.save(new Venue(request.name(), request.city(), request.address()));
        return CatalogMapper.toResponse(venue);
    }
}
