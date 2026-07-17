package com.seatsync.concierge.api.dto;

/** One retrieved corpus chunk backing an answer. */
public record SourceRef(String title, String snippet, double score) {
}
