package com.seatsync.concierge.api.dto;

/** Response of {@code POST /api/concierge/reindex}. */
public record ReindexResponse(int documentsIndexed) {
}
