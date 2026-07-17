package com.seatsync.auth.bootstrap;

import com.seatsync.auth.domain.User;
import com.seatsync.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Idempotent startup seeding of the well-known demo users. The fixed UUIDs are
 * part of the cross-service contract (catalog-service seeds events owned by
 * organizer {@code 00000000-0000-0000-0000-000000000002}).
 */
@Component
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ORGANIZER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID ATTENDEE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminPassword;

    public UserSeeder(UserRepository userRepository,
                      PasswordEncoder passwordEncoder,
                      @Value("${seatsync.seed.admin-password}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed(ADMIN_ID, "admin@seatsync.local", adminPassword, "SeatSync Admin", "ADMIN");
        seed(ORGANIZER_ID, "organizer@seatsync.local", "organizer1!", "Demo Organizer", "ORGANIZER");
        seed(ATTENDEE_ID, "attendee@seatsync.local", "attendee1!", "Demo Attendee", "ATTENDEE");
    }

    private void seed(UUID id, String email, String rawPassword, String fullName, String role) {
        if (userRepository.existsById(id) || userRepository.existsByEmail(email)) {
            return;
        }
        userRepository.save(new User(
                id, email, passwordEncoder.encode(rawPassword), fullName, List.of(role), Instant.now()));
        log.info("Seeded user {} ({})", email, role);
    }
}
