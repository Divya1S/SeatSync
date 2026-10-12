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

    /** Escape character for user-supplied text inside LIKE patterns. */
    static final char LIKE_ESCAPE = '\\';

    private EventSpecifications() {
    }

    /**
     * Public search: PUBLISHED events only, optional free-text query on name/description
     * (case-insensitive), optional category / venue-city / date-range filters on startsAt.
     * The free text is a bound parameter (no injection) AND LIKE-escaped, so a query of
     * {@code %} or {@code _} matches those literal characters instead of everything.
     */
    public static Specification<Event> publicSearch(String q, EventCategory category, String city,
                                                    Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));

            if (q != null && !q.isBlank()) {
                String like = "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, LIKE_ESCAPE),
                        cb.like(cb.lower(root.get("description")), like, LIKE_ESCAPE)));
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

    /** Neutralises LIKE metacharacters ({@code \ % _}) in user input. */
    static String escapeLike(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                out.append(LIKE_ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }
}
