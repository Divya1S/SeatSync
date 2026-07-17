package com.seatsync.concierge.api.dto;

import com.seatsync.concierge.chat.Confidence;

import java.util.List;

/** Response shape of {@code POST /api/concierge/chat} per CONVENTIONS §6.6. */
public record ChatResponse(
        String conversationId,
        String answer,
        List<SourceRef> sources,
        Confidence confidence,
        boolean escalatedToHuman) {
}
