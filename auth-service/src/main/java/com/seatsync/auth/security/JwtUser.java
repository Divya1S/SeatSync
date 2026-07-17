package com.seatsync.auth.security;

import java.util.List;
import java.util.UUID;

/**
 * Authenticated principal set by {@link JwtAuthFilter}. Roles are plain names
 * without the {@code ROLE_} prefix (the prefix is only used for authorities).
 */
public record JwtUser(UUID id, String email, String name, List<String> roles) {
}
