package com.seatsync.catalog.web.dto;

import java.util.UUID;

public record VenueResponse(UUID id, String name, String city, String address) {
}
