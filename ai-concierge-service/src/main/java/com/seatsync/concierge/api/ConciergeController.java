package com.seatsync.concierge.api;

import com.seatsync.concierge.api.dto.ChatRequest;
import com.seatsync.concierge.api.dto.ChatResponse;
import com.seatsync.concierge.api.dto.ReindexResponse;
import com.seatsync.concierge.chat.ConciergeService;
import com.seatsync.concierge.rag.CorpusIngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/concierge")
public class ConciergeController {

    private final ConciergeService conciergeService;
    private final CorpusIngestionService ingestionService;

    public ConciergeController(ConciergeService conciergeService, CorpusIngestionService ingestionService) {
        this.conciergeService = conciergeService;
        this.ingestionService = ingestionService;
    }

    /** Public (gateway rate-limits). Always 200 — degrades gracefully, never guesses. */
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return conciergeService.chat(request.message(), request.conversationId());
    }

    /** ADMIN only: clear the vector store and re-ingest the corpus. */
    @PostMapping("/reindex")
    public ReindexResponse reindex() {
        return new ReindexResponse(ingestionService.reindex());
    }
}
