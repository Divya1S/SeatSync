package com.seatsync.concierge.config;

/**
 * Whether the AI pipeline (OpenAI chat + embeddings) may be used at all.
 *
 * <p>Computed once at startup from the {@code OPENAI_API_KEY} environment
 * variable: the AI is enabled only when the key is present, non-empty and not
 * the offline placeholder {@value #OFFLINE_PLACEHOLDER_KEY} that
 * application.yml falls back to. Without a real key the service still starts
 * and degrades gracefully — it never crashes for lack of a key.
 */
public record AiAvailability(boolean enabled) {

    public static final String OFFLINE_PLACEHOLDER_KEY = "sk-dummy-offline";

    public static AiAvailability fromApiKey(String apiKey) {
        boolean enabled = apiKey != null
                && !apiKey.isBlank()
                && !OFFLINE_PLACEHOLDER_KEY.equals(apiKey.trim());
        return new AiAvailability(enabled);
    }
}
