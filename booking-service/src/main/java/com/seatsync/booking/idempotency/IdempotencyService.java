package com.seatsync.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.domain.IdempotencyKey;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import com.seatsync.booking.security.JwtUser;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Client-supplied {@code Idempotency-Key} semantics (conventions section 6.3),
 * scoped to (user, endpoint, key):
 *
 * <ul>
 *   <li>no header: process normally, this class stays out of the way;</li>
 *   <li>key must be 1-255 chars, else 400;</li>
 *   <li>first request: atomically claim the key (committed BEFORE the domain work
 *       starts), process, then store the final status + body — including 4xx
 *       errors — in a transaction of its own;</li>
 *   <li>replay (same key + same sha256 of the raw body): return the stored
 *       status/body untouched with {@code Idempotency-Replayed: true};</li>
 *   <li>same key + different body: 422;</li>
 *   <li>concurrent duplicate (claim exists, response not yet stored): 409
 *       "original request still in progress";</li>
 *   <li>5xx outcomes are never stored — the claim is deleted so a retry
 *       re-executes.</li>
 * </ul>
 *
 * <p><b>Why the outcome survives domain rollbacks:</b> the claim insert and the
 * outcome update run in REQUIRES_NEW transactions ({@link IdempotencyKeyRepository}),
 * and the outcome is written only after the controller-invoked domain call has
 * completed — i.e. after the domain transaction has already committed or rolled
 * back. A 409 produced by a rolled-back confirm transaction is therefore still
 * recorded and replayed.
 */
@Service
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    static final int MAX_KEY_LENGTH = 255;
    static final String IN_PROGRESS_DETAIL = "original request still in progress";
    static final String HASH_MISMATCH_DETAIL = "Idempotency-Key reused with a different request body";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyKeyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs {@code action} under Idempotency-Key semantics. Without the header the
     * action simply runs; with it, the result (or its stored twin) is returned.
     */
    public ResponseEntity<?> execute(JwtUser user, HttpServletRequest request, Supplier<ResponseEntity<?>> action) {
        String idemKey = request.getHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER);
        if (idemKey == null) {
            return action.get();
        }
        if (idemKey.isEmpty() || idemKey.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("Idempotency-Key must be between 1 and 255 characters");
        }
        String endpoint = request.getRequestURI();
        byte[] rawBody = IdempotencyBodyCacheFilter.cachedRawBody(request);
        String requestHash = sha256Hex(rawBody != null ? rawBody : new byte[0]);
        UUID userId = user.id();

        boolean claimed = repository.tryClaim(
                UUID.randomUUID(), userId, endpoint, idemKey, requestHash, Instant.now()) > 0;
        if (!claimed) {
            return replay(userId, endpoint, idemKey, requestHash);
        }

        ResponseEntity<?> response;
        try {
            response = action.get();
        } catch (ApiException e) {
            if (e.getStatus().is5xxServerError()) {
                abandonClaim(userId, endpoint, idemKey);
            } else {
                storeOutcome(userId, endpoint, idemKey, e.getStatus().value(),
                        problemJson(e.getStatus(), e.getMessage(), endpoint));
            }
            throw e;
        } catch (OptimisticLockingFailureException e) {
            // The ApiExceptionHandler safety net renders this as 409 "Seat was just
            // taken"; record exactly that so the replay matches the client's view.
            storeOutcome(userId, endpoint, idemKey, HttpStatus.CONFLICT.value(),
                    problemJson(HttpStatus.CONFLICT, "Seat was just taken", endpoint));
            throw e;
        } catch (RuntimeException | Error e) {
            // Renders as a 5xx: never stored, the claim is removed so a retry re-executes.
            abandonClaim(userId, endpoint, idemKey);
            throw e;
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(response.getBody());
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize response body for idempotency replay on {}: {}", endpoint, e.getMessage());
            abandonClaim(userId, endpoint, idemKey);
            return response;
        }
        storeOutcome(userId, endpoint, idemKey, response.getStatusCode().value(), body);
        return response;
    }

    /** Claim already exists: hash-check, then replay the stored outcome or report in-flight. */
    private ResponseEntity<?> replay(UUID userId, String endpoint, String idemKey, String requestHash) {
        // Fresh read in its own transaction; a vanished row means the original
        // attempt ended 5xx and deleted its claim — retrying later is correct.
        IdempotencyKey existing = repository.findByUserIdAndEndpointAndIdemKey(userId, endpoint, idemKey)
                .orElseThrow(() -> ApiException.conflict(IN_PROGRESS_DETAIL));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, HASH_MISMATCH_DETAIL);
        }
        if (existing.getResponseStatus() == null) {
            throw ApiException.conflict(IN_PROGRESS_DETAIL);
        }
        String storedBody = existing.getResponseBody() != null ? existing.getResponseBody() : "";
        // byte[] body + explicit content type: the stored JSON is written verbatim.
        return ResponseEntity.status(existing.getResponseStatus())
                .header(REPLAYED_HEADER, "true")
                .contentType(MediaType.APPLICATION_JSON)
                .body(storedBody.getBytes(StandardCharsets.UTF_8));
    }

    private void storeOutcome(UUID userId, String endpoint, String idemKey, int status, String body) {
        try {
            int updated = repository.storeResponse(userId, endpoint, idemKey, status, body);
            if (updated == 0) {
                log.warn("Idempotency claim for user {} on {} vanished before its outcome was stored", userId, endpoint);
            }
        } catch (RuntimeException e) {
            // Losing the replay record must never lose the client's response.
            log.warn("Failed to store idempotency outcome for user {} on {}: {}", userId, endpoint, e.getMessage());
            abandonClaim(userId, endpoint, idemKey);
        }
    }

    private void abandonClaim(UUID userId, String endpoint, String idemKey) {
        try {
            repository.deleteClaim(userId, endpoint, idemKey);
        } catch (RuntimeException e) {
            // Worst case the orphaned claim answers 409-in-flight until the 24h purge.
            log.warn("Failed to delete idempotency claim for user {} on {}: {}", userId, endpoint, e.getMessage());
        }
    }

    /** Mirrors ApiExceptionHandler's RFC 7807 rendering so replays are byte-faithful. */
    private String problemJson(HttpStatus status, String detail, String requestUri) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(requestUri));
        try {
            return objectMapper.writeValueAsString(problem);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                    + "\",\"status\":" + status.value() + ",\"instance\":\"" + requestUri + "\"}";
        }
    }

    /** sha256 hex (lowercase) of the raw request body, per conventions section 6.3. */
    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
