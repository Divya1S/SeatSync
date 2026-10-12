package com.seatsync.auth.service;

import com.seatsync.auth.api.dto.LoginRequest;
import com.seatsync.auth.api.dto.RefreshRequest;
import com.seatsync.auth.api.dto.RegisterRequest;
import com.seatsync.auth.api.dto.TokenResponse;
import com.seatsync.auth.api.dto.UserResponse;
import com.seatsync.auth.domain.RefreshToken;
import com.seatsync.auth.domain.User;
import com.seatsync.auth.repository.RefreshTokenRepository;
import com.seatsync.auth.repository.UserRepository;
import com.seatsync.auth.security.JwtService;
import com.seatsync.auth.service.exception.DuplicateEmailException;
import com.seatsync.auth.service.exception.InvalidCredentialsException;
import com.seatsync.auth.service.exception.InvalidRefreshTokenException;
import com.seatsync.auth.service.exception.RoleNotAllowedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Set<String> SELF_REGISTER_ROLES = Set.of("ATTENDEE", "ORGANIZER");

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String role = request.role().trim().toUpperCase();
        if (role.equals("ADMIN")) {
            throw new RoleNotAllowedException(role);
        }
        if (!SELF_REGISTER_ROLES.contains(role)) {
            throw new IllegalArgumentException("role must be ATTENDEE or ORGANIZER");
        }
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        User user = new User(
                UUID.randomUUID(),
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                List.of(role),
                Instant.now());
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // lost the race against a concurrent register with the same email
            throw new DuplicateEmailException(email);
        }
        return toUserResponse(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    /**
     * {@code noRollbackFor}: the family revocation performed on reuse detection must
     * COMMIT even though the request still ends in a 401; a plain rollback would
     * silently undo the one write that makes reuse detection worth anything.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenResponse refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtService.parse(request.refreshToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidRefreshTokenException();
        }
        if (!JwtService.TYP_REFRESH.equals(claims.get("typ", String.class))) {
            throw new InvalidRefreshTokenException();
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256Hex(request.refreshToken()))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (stored.isRevoked()) {
            // A revoked token being presented again means either a stale client or a
            // stolen token replayed after the legitimate rotation. Both are handled the
            // same way (OAuth 2.0 refresh-token rotation guidance): revoke the whole
            // family so the attacker's copy of the NEW token is useless too.
            int revoked = refreshTokenRepository.revokeAllByUserId(stored.getUserId());
            log.warn("Refresh token reuse detected for user {}: revoked {} live token(s)",
                    stored.getUserId(), revoked);
            throw new InvalidRefreshTokenException();
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);
        // rotate: revoke the used token, then issue a brand-new pair
        stored.revoke();
        refreshTokenRepository.save(stored);
        return issueTokens(user);
    }

    /**
     * Revokes the presented refresh token so it can never be exchanged again. Always
     * succeeds: a malformed, unknown or already-revoked token reveals nothing to the
     * caller. Access tokens stay valid until they expire (15 min); revocation of
     * access tokens would require per-request state, which is a documented trade-off.
     */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(sha256Hex(refreshToken)).ifPresent(stored -> {
            if (!stored.isRevoked()) {
                stored.revoke();
                refreshTokenRepository.save(stored);
            }
        });
    }

    private TokenResponse issueTokens(User user) {
        String accessToken = jwtService.issueAccessToken(
                user.getId(), user.getEmail(), user.getFullName(), user.getRoleList());
        String refreshToken = jwtService.issueRefreshToken(
                user.getId(), user.getEmail(), user.getFullName(), user.getRoleList());
        Instant now = Instant.now();
        refreshTokenRepository.save(new RefreshToken(
                UUID.randomUUID(),
                user.getId(),
                sha256Hex(refreshToken),
                now.plusSeconds(jwtService.getRefreshTtlSeconds()),
                now));
        return new TokenResponse(
                accessToken,
                refreshToken,
                "Bearer",
                jwtService.getAccessTtlSeconds(),
                toUserResponse(user));
    }

    /**
     * Entity-to-DTO mapping lives in the service layer so the api package
     * never depends on JPA entities (ArchUnit rule 2, CONVENTIONS §8.1).
     */
    private static UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRoleList());
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
