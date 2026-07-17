package com.seatsync.catalog.repository;

import com.seatsync.catalog.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

    @EntityGraph(attributePaths = {"venue", "sections"})
    Optional<Event> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"venue"})
    List<Event> findByOrganizerIdOrderByStartsAtAsc(UUID organizerId);

    @Override
    @EntityGraph(attributePaths = {"venue"})
    Page<Event> findAll(Specification<Event> spec, Pageable pageable);
}
