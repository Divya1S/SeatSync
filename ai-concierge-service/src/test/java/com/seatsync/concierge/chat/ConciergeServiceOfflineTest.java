package com.seatsync.concierge.chat;

import com.seatsync.concierge.api.dto.ChatResponse;
import com.seatsync.concierge.config.AiAvailability;
import com.seatsync.concierge.tools.ToolInvocationTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The offline degradation contract: without an OpenAI key, /chat must return
 * the graceful shape and never touch the vector store or the LLM.
 */
class ConciergeServiceOfflineTest {

    private ChatClient chatClient;
    private VectorStore vectorStore;
    private ConciergeService service;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        vectorStore = mock(VectorStore.class);
        service = new ConciergeService(
                AiAvailability.fromApiKey("sk-dummy-offline"),
                chatClient,
                vectorStore,
                new ToolInvocationTracker());
    }

    @Test
    void offlineChatReturnsGracefulShape() {
        ChatResponse response = service.chat("How do refunds work?", null);

        assertThat(response.answer()).isEqualTo(ConciergeService.OFFLINE_ANSWER);
        assertThat(response.answer()).contains("support@seatsync.local");
        assertThat(response.sources()).isEmpty();
        assertThat(response.confidence()).isEqualTo(Confidence.LOW);
        assertThat(response.escalatedToHuman()).isTrue();
    }

    @Test
    void offlineChatGeneratesConversationIdWhenAbsent() {
        ChatResponse response = service.chat("hello", null);

        assertThat(response.conversationId()).isNotBlank();
        // must be a parseable UUID
        assertThat(UUID.fromString(response.conversationId())).isNotNull();
    }

    @Test
    void offlineChatKeepsProvidedConversationId() {
        String conversationId = UUID.randomUUID().toString();

        ChatResponse response = service.chat("hello again", conversationId);

        assertThat(response.conversationId()).isEqualTo(conversationId);
    }

    @Test
    void offlineChatNeverTouchesLlmOrVectorStore() {
        service.chat("anything", null);

        verifyNoInteractions(chatClient, vectorStore);
    }

    @Test
    void runtimeAiFailureFallsBackToSameGracefulShape() {
        String conversationId = UUID.randomUUID().toString();

        ChatResponse response = service.chatFallback("boom", conversationId,
                new IllegalStateException("provider down"));

        assertThat(response.conversationId()).isEqualTo(conversationId);
        assertThat(response.answer()).isEqualTo(ConciergeService.OFFLINE_ANSWER);
        assertThat(response.sources()).isEmpty();
        assertThat(response.confidence()).isEqualTo(Confidence.LOW);
        assertThat(response.escalatedToHuman()).isTrue();
    }
}
