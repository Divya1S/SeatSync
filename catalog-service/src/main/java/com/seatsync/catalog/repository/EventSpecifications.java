package com.seatsync.catalog.repository;

import com.seatsync.catalog.domain.Event;
import com.seatsync.catalog.domain.EventCategory;
import com.seatsync.catalog.domain.EventStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EventSpecifications {

    private EventSpecifications() {
    }

    /**
     * Public search: PUBLISHED events only, optional free-text query on name/description
     * (case-insensitive), optional category / venue-city / date-range filters on startsAt.
     */
    public static Specification<Event> publicSearch(String q, EventCategory category, String city,
                                                    Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));

            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("description")), like)));
            }
            if (category != null) {
                predicates.add(cb.equal(root.get("category"), category));
            }
            if (city != null && !city.isBlank()) {
                predicates.add(cb.equal(cb.lower(root.join("venue").get("city")),
                        city.trim().toLowerCase(Locale.ROOT)));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startsAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("startsAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
