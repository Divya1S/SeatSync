package com.seatsync.catalog.security;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal populated by {@link JwtAuthFilter} from the access-token claims
 * (convention: sub = user id, email, name, roles without ROLE_ prefix).
 */
public record JwtUser(UUID id, String email, String name, List<String> roles) {

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}
