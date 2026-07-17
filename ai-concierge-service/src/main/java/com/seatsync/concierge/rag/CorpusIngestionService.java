package com.seatsync.concierge.rag;

import com.seatsync.concierge.chat.ConciergeOfflineException;
import com.seatsync.concierge.config.AiAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the markdown corpus from {@code classpath:corpus/*.md}, splits it with
 * {@link TokenTextSplitter}, stamps each chunk with a human-readable
 * {@code title} (derived from the filename) and stores it in pgvector.
 * Embedding happens inside {@code vectorStore.add(...)}, so nothing here may
 * run while the AI is offline.
 */
@Service
public class CorpusIngestionService {

    static final String VECTOR_TABLE = "vector_store";
    private static final String CORPUS_PATTERN = "classpath:corpus/*.md";
    private static final Set<String> LOWERCASE_WORDS = Set.of("and", "or", "the", "of", "a", "an", "to", "in", "for");

    private static final Logger log = LoggerFactory.getLogger(CorpusIngestionService.class);

    private final AiAvailability aiAvailability;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public CorpusIngestionService(AiAvailability aiAvailability,
                                  VectorStore vectorStore,
                                  JdbcTemplate jdbcTemplate) {
        this.aiAvailability = aiAvailability;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Startup ingestion: only when the store is empty (idempotent across restarts). */
    public void ingestIfEmpty() {
        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + VECTOR_TABLE, Long.class);
        if (rows != null && rows > 0) {
            log.info("Vector store already holds {} chunks — skipping corpus ingestion", rows);
            return;
        }
        int indexed = ingestCorpus();
        log.info("Ingested corpus into empty vector store: {} chunks", indexed);
    }

    /**
     * Full re-ingestion (ADMIN endpoint): clears the vector table and re-reads
     * the corpus.
     *
     * @return number of chunks indexed
     * @throws ConciergeOfflineException when no OpenAI key is configured
     */
    public int reindex() {
        if (!aiAvailability.enabled()) {
            throw new ConciergeOfflineException();
        }
        int deleted = jdbcTemplate.update("DELETE FROM " + VECTOR_TABLE);
        int indexed = ingestCorpus();
        log.info("Reindexed corpus: {} old chunks deleted, {} chunks indexed", deleted, indexed);
        return indexed;
    }

    private int ingestCorpus() {
        List<Document> documents = new ArrayList<>();
        for (Resource resource : corpusResources()) {
            TextReader reader = new TextReader(resource);
            reader.getCustomMetadata().put("title", humanTitle(resource.getFilename()));
            documents.addAll(reader.get());
        }
        List<Document> chunks = new TokenTextSplitter().apply(documents);
        vectorStore.add(chunks);
        return chunks.size();
    }

    private static Resource[] corpusResources() {
        try {
            return new PathMatchingResourcePatternResolver().getResources(CORPUS_PATTERN);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to read corpus resources", ex);
        }
    }

    /** {@code refund-and-cancellation-policy.md} → {@code Refund and Cancellation Policy}. */
    static String humanTitle(String filename) {
        if (filename == null || filename.isBlank()) {
            return "SeatSync documentation";
        }
        String base = filename.replaceFirst("\\.md$", "");
        String[] words = base.split("-");
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i].toLowerCase(Locale.ROOT);
            if (word.isBlank()) {
                continue;
            }
            if (!title.isEmpty()) {
                title.append(' ');
            }
            if (word.equals("faq")) {
                title.append("FAQ");
            } else if (i > 0 && LOWERCASE_WORDS.contains(word)) {
                title.append(word);
            } else {
                title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return title.toString();
    }
}
