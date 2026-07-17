package com.seatsync.booking.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatsync.booking.domain.IdempotencyKey;
import com.seatsync.booking.error.ApiException;
import com.seatsync.booking.repo.IdempotencyKeyRepository;
import com.seatsync.booking.security.JwtUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Idempotency-Key claim/replay state machine (conventions section 6.3):
 * claim -> execute -> store; lost claim -> hash check -> replay / in-flight;
 * 5xx never stored.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String ENDPOINT = "/api/holds";
    private static final String KEY = "client-key-1";
    private static final byte[] RAW_BODY = "{\"eventId\":\"e\",\"seatId\":\"A-1-1\"}".getBytes(StandardCharsets.UTF_8);
    private static final String RAW_BODY_HASH = IdempotencyService.sha256Hex(RAW_BODY);

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService service;

    private final JwtUser user = new JwtUser(UUID.randomUUID(), "u@test.io", "U", List.of("ATTENDEE"));

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, new ObjectMapper());
    }

    private MockHttpServletRequest request(String idemKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", ENDPOINT);
        request.setRequestURI(ENDPOINT);
        if (idemKey != null) {
            request.addHeader(IdempotencyBodyCacheFilter.IDEMPOTENCY_KEY_HEADER, idemKey);
        }
        request.setAttribute(IdempotencyBodyCacheFilter.RAW_BODY_ATTRIBUTE, RAW_BODY);
        return request;
    }

    private static Supplier<ResponseEntity<?>> action(ResponseEntity<?> response, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return response;
        };
    }

    @Test
    void withoutHeaderTheActionRunsAndTheStoreIsNeverTouched() {
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<?> response = service.execute(user, request(null),
                action(ResponseEntity.status(201).body("fresh"), calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verifyNoInteractions(repository);
    }

    @Test
    void emptyKeyIsRejectedWith400BeforeAnythingRuns() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> service.execute(user, request(""), action(ResponseEntity.ok("x"), calls)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(calls.get()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void overlongKeyIsRejectedWith400BeforeAnythingRuns() {
        AtomicInteger calls = new AtomicInteger();
        String key256 = "k".repeat(256);
        assertThatThrownBy(() -> service.execute(user, request(key256), action(ResponseEntity.ok("x"), calls)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(calls.get()).isZero();
        verifyNoInteractions(repository);
    }

    @Test
    void wonClaimExecutesTheActionAndStoresStatusAndBody() {
        when(repository.tryClaim(any(), eq(user.id()), eq(ENDPOINT), eq(KEY), eq(RAW_BODY_HASH), any(Instant.class)))
                .thenReturn(1);
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<?> response = service.execute(user, request(KEY),
                action(ResponseEntity.status(201).body(java.util.Map.of("id", "hold-1")), calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(repository).storeResponse(user.id(), ENDPOINT, KEY, 201, "{\"id\":\"hold-1\"}");
    }

    @Test
    void lostClaimWithStoredResponseReplaysWithoutExecuting() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByUserIdAndEndpointAndIdemKey(user.id(), ENDPOINT, KEY))
                .thenReturn(Optional.of(row(RAW_BODY_HASH, 201, "{\"id\":\"hold-1\"}")));
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<?> response = service.execute(user, request(KEY),
                action(ResponseEntity.status(201).body("MUST NOT RUN"), calls));

        assertThat(calls.get()).as("replay must not re-execute").isZero();
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst(IdempotencyService.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(new String((byte[]) response.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"id\":\"hold-1\"}");
        verify(repository, never()).storeResponse(any(), any(), any(), anyInt(), any());
    }

    @Test
    void lostClaimWithDifferentRequestHashIs422() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByUserIdAndEndpointAndIdemKey(user.id(), ENDPOINT, KEY))
                .thenReturn(Optional.of(row("a-different-hash", 201, "{}")));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(user, request(KEY), action(ResponseEntity.ok("x"), calls)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getMessage()).contains("different request body");
                });
        assertThat(calls.get()).isZero();
    }

    @Test
    void lostClaimStillInFlightIs409() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByUserIdAndEndpointAndIdemKey(user.id(), ENDPOINT, KEY))
                .thenReturn(Optional.of(row(RAW_BODY_HASH, null, null)));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> service.execute(user, request(KEY), action(ResponseEntity.ok("x"), calls)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("still in progress");
                });
        assertThat(calls.get()).isZero();
    }

    @Test
    void lostClaimWhoseRowVanishedIs409InFlight() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(0);
        when(repository.findByUserIdAndEndpointAndIdemKey(user.id(), ENDPOINT, KEY))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(user, request(KEY), action(ResponseEntity.ok("x"), new AtomicInteger())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void apiException4xxIsStoredAsProblemAndRethrown() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Supplier<ResponseEntity<?>> failing = () -> {
            throw ApiException.conflict("Seat A-1-1 is already held");
        };

        assertThatThrownBy(() -> service.execute(user, request(KEY), failing))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(repository).storeResponse(eq(user.id()), eq(ENDPOINT), eq(KEY), eq(409),
                contains("Seat A-1-1 is already held"));
        verify(repository, never()).deleteClaim(any(), any(), any());
    }

    @Test
    void optimisticLockLossIsStoredAsThe409TheHandlerRenders() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Supplier<ResponseEntity<?>> failing = () -> {
            throw new OptimisticLockingFailureException("version clash");
        };

        assertThatThrownBy(() -> service.execute(user, request(KEY), failing))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(repository).storeResponse(eq(user.id()), eq(ENDPOINT), eq(KEY), eq(409),
                contains("Seat was just taken"));
    }

    @Test
    void apiException5xxDeletesTheClaimAndIsNeverStored() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Supplier<ResponseEntity<?>> failing = () -> {
            throw ApiException.serviceUnavailable("Event catalog temporarily unavailable");
        };

        assertThatThrownBy(() -> service.execute(user, request(KEY), failing))
                .isInstanceOf(ApiException.class);

        verify(repository).deleteClaim(user.id(), ENDPOINT, KEY);
        verify(repository, never()).storeResponse(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void unexpectedExceptionDeletesTheClaimAndIsNeverStored() {
        when(repository.tryClaim(any(), any(), any(), any(), any(), any())).thenReturn(1);
        Supplier<ResponseEntity<?>> failing = () -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> service.execute(user, request(KEY), failing))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).deleteClaim(user.id(), ENDPOINT, KEY);
        verify(repository, never()).storeResponse(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void sha256HexMatchesKnownVectors() {
        assertThat(IdempotencyService.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(IdempotencyService.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    private IdempotencyKey row(String hash, Integer status, String body) {
        return new IdempotencyKey(UUID.randomUUID(), user.id(), ENDPOINT, KEY, hash, status, body, Instant.now());
    }
}
