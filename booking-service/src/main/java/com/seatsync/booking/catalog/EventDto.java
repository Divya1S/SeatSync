package com.seatsync.booking.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Subset of the catalog Event payload that booking cares about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventDto(UUID id, String name, String status) {
}
