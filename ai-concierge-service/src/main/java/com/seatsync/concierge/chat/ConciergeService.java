package com.seatsync.concierge.chat;

import com.seatsync.concierge.api.dto.ChatResponse;
import com.seatsync.concierge.api.dto.SourceRef;
import com.seatsync.concierge.config.AiAvailability;
import com.seatsync.concierge.config.AiConfig;
import com.seatsync.concierge.tools.ToolInvocationTracker;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The concierge chat pipeline.
 *
 * <p>Offline (no OpenAI key) the service short-circuits to the graceful
 * degradation shape. Online, the whole pipeline (query embedding for source
 * capture, RAG-advised LLM call, tool calls) runs inside the {@code llm}
 * circuit breaker: ANY runtime AI failure — bad key, provider down, open
 * breaker — falls back to the same graceful shape instead of a 500.
 */
@Service
public class ConciergeService {

    public static final String OFFLINE_ANSWER =
            "The AI concierge is offline right now. Please email support@seatsync.local "
                    + "and a human will help you.";

    static final String EMPTY_ANSWER_FALLBACK =
            "I am not sure about that. Please email support@seatsync.local and a human will help you.";

    private static final int SNIPPET_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(ConciergeService.class);

    private final AiAvailability aiAvailability;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ToolInvocationTracker toolTracker;

    public ConciergeService(AiAvailability aiAvailability,
                            ChatClient conciergeChatClient,
                            VectorStore vectorStore,
                            ToolInvocationTracker toolTracker) {
        this.aiAvailability = aiAvailability;
        this.chatClient = conciergeChatClient;
        this.vectorStore = vectorStore;
        this.toolTracker = toolTracker;
    }

    @CircuitBreaker(name = "llm", fallbackMethod = "chatFallback")
    public ChatResponse chat(String message, String requestedConversationId) {
        String conversationId = resolveConversationId(requestedConversationId);
        if (!aiAvailability.enabled()) {
            return offlineResponse(conversationId);
        }

        // Capture the retrieved documents + scores ourselves (the QA advisor
        // does not expose them) so we can return sources[] and grade confidence.
        List<Document> retrieved = vectorStore.similaritySearch(SearchRequest.builder()
                .query(message)
                .topK(AiConfig.RAG_TOP_K)
                .similarityThreshold(AiConfig.RAG_SIMILARITY_THRESHOLD)
                .build());
        List<SourceRef> sources = retrieved.stream().map(ConciergeService::toSource).toList();
        double topScore = retrieved.stream()
                .map(Document::getScore)
                .filter(score -> score != null)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        toolTracker.reset();
        String answer;
        boolean toolInvoked;
        try {
            answer = chatClient.prompt()
                    .user(message)
                    .advisors(advisors -> advisors.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } finally {
            toolInvoked = toolTracker.wasInvoked();
            toolTracker.clear();
        }
        if (answer == null || answer.isBlank()) {
            answer = EMPTY_ANSWER_FALLBACK;
        }

        Confidence confidence = ConfidencePolicy.assess(toolInvoked, topScore);
        boolean escalated = ConfidencePolicy.shouldEscalate(confidence, answer);
        return new ChatResponse(conversationId, answer, sources, confidence, escalated);
    }

    /**
     * resilience4j fallback: same graceful shape as the no-key degradation,
     * never a 500. Also serves CallNotPermittedException when the breaker is open.
     */
    ChatResponse chatFallback(String message, String requestedConversationId, Throwable failure) {
        log.warn("AI pipeline failed, degrading to offline response: {}", failure.toString());
        return offlineResponse(resolveConversationId(requestedConversationId));
    }

    private static ChatResponse offlineResponse(String conversationId) {
        return new ChatResponse(conversationId, OFFLINE_ANSWER, List.of(), Confidence.LOW, true);
    }

    private static String resolveConversationId(String requested) {
        return requested == null || requested.isBlank() ? UUID.randomUUID().toString() : requested;
    }

    private static SourceRef toSource(Document document) {
        Object title = document.getMetadata().get("title");
        String text = document.getText() == null ? "" : document.getText();
        String normalized = text.replaceAll("\\s+", " ").trim();
        String snippet = normalized.length() <= SNIPPET_LENGTH
                ? normalized
                : normalized.substring(0, SNIPPET_LENGTH) + "…";
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new SourceRef(title == null ? "SeatSync documentation" : title.toString(), snippet, score);
    }
}
