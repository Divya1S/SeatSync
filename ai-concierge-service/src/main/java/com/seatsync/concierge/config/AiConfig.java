package com.seatsync.concierge.config;

import com.seatsync.concierge.tools.ConciergeTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The grounded chat pipeline: RAG over the pgvector corpus
 * ({@link QuestionAnswerAdvisor}), windowed in-memory conversation memory
 * keyed by conversationId, and the live-data tools.
 */
@Configuration
public class AiConfig {

    /** RAG retrieval settings — shared by the QA advisor and the source capture. */
    public static final int RAG_TOP_K = 4;
    public static final double RAG_SIMILARITY_THRESHOLD = 0.5;
    public static final int MEMORY_MAX_MESSAGES = 20;

    public static final String SYSTEM_PROMPT = """
            You are the SeatSync concierge. Answer ONLY using the provided context documents \
            and tool results. If the context and tools do not contain the answer, reply exactly \
            that you are not sure and direct the user to support@seatsync.local. Never invent \
            events, prices, seat counts or policies. Keep answers under 150 words. When you used \
            a tool for live data, say the data is live.""";

    @Bean
    public AiAvailability aiAvailability(Environment environment) {
        return AiAvailability.fromApiKey(environment.getProperty("OPENAI_API_KEY"));
    }

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_MAX_MESSAGES)
                .build();
    }

    @Bean
    public ChatClient conciergeChatClient(ChatClient.Builder chatClientBuilder,
                                          VectorStore vectorStore,
                                          ChatMemory chatMemory,
                                          ConciergeTools conciergeTools) {
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(RAG_TOP_K)
                                        .similarityThreshold(RAG_SIMILARITY_THRESHOLD)
                                        .build())
                                .build())
                .defaultTools(conciergeTools)
                .build();
    }
}
