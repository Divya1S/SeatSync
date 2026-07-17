package com.seatsync.booking.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing for the chaos scenarios. Subclasses declare their own
 * {@code @SpringBootTest} + {@code @DynamicPropertySource} (each needs a
 * different degradation profile and its own database on the chaos Postgres).
 *
 * <p>The one non-negotiable: {@link #assertNoOversell(UUID, int)} runs the
 * conventions-8.1 invariant at the end of EVERY scenario. Availability may
 * degrade; correctness may not.
 */
public abstract class AbstractChaosIT {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ResponseEntity<Map<String, Object>> postJson(String path, Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearerToken);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /** Seat ids A-1-1 .. A-1-{count}, seeded straight into inventory (no catalog round trip). */
    protected void seedSeats(UUID eventId, int count, BigDecimal price) {
        for (int n = 1; n <= count; n++) {
            jdbc.update("insert into seat_inventory "
                            + "(id, event_id, seat_id, section, row_label, seat_number, price, status, version) "
                            + "values (?, ?, ?, 'A', '1', ?, ?, 'AVAILABLE', 0) "
                            + "on conflict (event_id, seat_id) do nothing",
                    UUID.randomUUID(), eventId, seat(n), n, price);
        }
    }

    protected static String seat(int n) {
        return "A-1-" + n;
    }

    /**
     * The conventions-8.1 invariant, asserted after every scenario:
     * no seat anywhere has two CONFIRMED bookings, and for the scenario's event
     * the booked seats never exceed capacity (with confirmed bookings and BOOKED
     * inventory rows in lockstep — the chaos scenarios never cancel).
     */
    protected void assertNoOversell(UUID eventId, int capacity) {
        List<Map<String, Object>> doubleConfirmed = jdbc.queryForList(
                "SELECT seat_id FROM bookings WHERE status='CONFIRMED' "
                        + "GROUP BY event_id, seat_id HAVING count(*)>1");
        assertThat(doubleConfirmed)
                .as("no seat may ever have more than one CONFIRMED booking")
                .isEmpty();

        Integer confirmed = jdbc.queryForObject(
                "select count(*) from bookings where event_id = ? and status = 'CONFIRMED'",
                Integer.class, eventId);
        Integer booked = jdbc.queryForObject(
                "select count(*) from seat_inventory where event_id = ? and status = 'BOOKED'",
                Integer.class, eventId);
        assertThat(confirmed).as("confirmed bookings must never exceed capacity")
                .isLessThanOrEqualTo(capacity);
        assertThat(booked).as("booked seats must never exceed capacity")
                .isLessThanOrEqualTo(capacity);
        assertThat(confirmed).as("confirmed bookings and BOOKED inventory rows must be in lockstep")
                .isEqualTo(booked);
    }

    /** One-line verdict per scenario; transcribed into docs/chaos-and-availability.md. */
    protected static void verdict(String scenario, String availability) {
        System.out.println("CHAOS-VERDICT [" + scenario + "] correctness=HELD availability=" + availability);
    }
}
