package com.seatsync.concierge.chat;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation store that forgets idle conversations and caps the
 * number it keeps. Spring AI's {@code InMemoryChatMemoryRepository} never
 * evicts, which on a public chat endpoint is an unbounded memory leak keyed by
 * client-supplied conversation ids. Idle conversations are dropped after
 * {@code idleTtl}; if the store still exceeds {@code maxConversations}, the
 * least recently used ones go first. Sweeps are amortised: at most one scan
 * per minute, triggered by normal reads and writes.
 *
 * <p>Still per instance: with several replicas a conversation routed elsewhere
 * loses its context, which a shared (JDBC or Redis) repository would fix.
 */
public class ExpiringChatMemoryRepository implements ChatMemoryRepository {

    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);

    private record Entry(List<Message> messages, Instant lastAccess) {
    }

    private final Duration idleTtl;
    private final int maxConversations;
    private final Clock clock;
    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private volatile Instant lastSweep;

    public ExpiringChatMemoryRepository(Duration idleTtl, int maxConversations, Clock clock) {
        if (idleTtl == null || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException("idleTtl must be positive");
        }
        if (maxConversations <= 0) {
            throw new IllegalArgumentException("maxConversations must be positive");
        }
        this.idleTtl = idleTtl;
        this.maxConversations = maxConversations;
        this.clock = clock;
        this.lastSweep = clock.instant();
    }

    @Override
    public List<String> findConversationIds() {
        sweepIfDue();
        return List.copyOf(store.keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        sweepIfDue();
        Entry entry = store.get(conversationId);
        if (entry == null) {
            return List.of();
        }
        store.put(conversationId, new Entry(entry.messages(), clock.instant()));
        // Mutable copy: callers (MessageWindowChatMemory) may append to the returned list.
        return new ArrayList<>(entry.messages());
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        sweepIfDue();
        store.put(conversationId, new Entry(List.copyOf(messages), clock.instant()));
        enforceCap();
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        store.remove(conversationId);
    }

    int size() {
        return store.size();
    }

    private void sweepIfDue() {
        Instant now = clock.instant();
        if (Duration.between(lastSweep, now).compareTo(SWEEP_INTERVAL) < 0) {
            return;
        }
        lastSweep = now;
        Instant cutoff = now.minus(idleTtl);
        store.entrySet().removeIf(entry -> entry.getValue().lastAccess().isBefore(cutoff));
    }

    private void enforceCap() {
        if (store.size() <= maxConversations) {
            return;
        }
        store.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastAccess()))
                .limit(store.size() - maxConversations)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(store::remove);
    }
}
