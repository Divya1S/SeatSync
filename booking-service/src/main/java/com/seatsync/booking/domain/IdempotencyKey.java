package com.seatsync.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One client-supplied Idempotency-Key claim, scoped to (user, endpoint, key)
 * (conventions section 6.3). {@code responseStatus}/{@code responseBody} are NULL
 * while the original request is still in flight; once processing finishes with a
 * status below 500 they hold the exact response to replay. 5xx outcomes delete
 * the row instead — a retry must re-execute.
 *
 * <p>Rows are written via native/bulk queries only (the claim insert must be an
 * atomic {@code ON CONFLICT DO NOTHING}); this entity is read-only.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "endpoint", nullable = false, length = 64)
    private String endpoint;

    @Column(name = "idem_key", nullable = false, length = 255)
    private String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
    }

    /** Test/fixture constructor; production rows are inserted via the native claim query. */
    public IdempotencyKey(UUID id, UUID userId, String endpoint, String idemKey, String requestHash,
                          Integer responseStatus, String responseBody, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.endpoint = endpoint;
        this.idemKey = idemKey;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getIdemKey() {
        return idemKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
