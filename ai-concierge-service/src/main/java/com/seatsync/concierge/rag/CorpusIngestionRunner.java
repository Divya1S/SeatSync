package com.seatsync.concierge.rag;

import com.seatsync.concierge.config.AiAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup ingestion. When the AI is offline (no OpenAI key) this is a no-op:
 * startup must never fail and must never call the embedding API without a key.
 */
@Component
public class CorpusIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CorpusIngestionRunner.class);

    private final AiAvailability aiAvailability;
    private final CorpusIngestionService ingestionService;

    public CorpusIngestionRunner(AiAvailability aiAvailability, CorpusIngestionService ingestionService) {
        this.aiAvailability = aiAvailability;
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!aiAvailability.enabled()) {
            log.warn("OPENAI_API_KEY not configured — AI concierge runs in offline degradation mode, "
                    + "corpus ingestion skipped");
            return;
        }
        ingestionService.ingestIfEmpty();
    }
}
