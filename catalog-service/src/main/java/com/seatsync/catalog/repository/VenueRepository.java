package com.seatsync.catalog.repository;

import com.seatsync.catalog.domain.Venue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

    List<Venue> findAllByOrderByNameAsc();
}
