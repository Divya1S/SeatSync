package com.seatsync.booking.security;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal extracted from the JWT, per SeatSync conventions (section 4).
 */
public record JwtUser(UUID id, String email, String name, List<String> roles) {
}
