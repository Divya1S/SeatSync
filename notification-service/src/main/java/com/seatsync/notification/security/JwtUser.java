package com.seatsync.notification.security;

import java.util.List;
import java.util.UUID;

public record JwtUser(UUID id, String email, String name, List<String> roles) {
}
