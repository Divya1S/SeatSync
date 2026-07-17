package com.seatsync.auth.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Pure edge DTO — deliberately free of any dependency on JPA entities;
 * the service layer owns the entity-to-DTO mapping.
 */
public record UserResponse(UUID id, String email, String fullName, List<String> roles) {
}
