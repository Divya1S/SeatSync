package com.seatsync.concierge.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpiringChatMemoryRepositoryTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private Instant now = Instant.parse("2026-10-11T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final ManualClock clock = new ManualClock();

    @Test
    void storesAndReturnsMessagesPerConversation() {
        ExpiringChatMemoryRepository repo = new ExpiringChatMemoryRepository(Duration.ofMinutes(30), 100, clock);

        repo.saveAll("c1", List.of(new UserMessage("hello")));

        assertThat(repo.findByConversationId("c1")).extracting(m -> m.getText()).containsExactly("hello");
        assertThat(repo.findByConversationId("unknown")).isEmpty();
        assertThat(repo.findConversationIds()).containsExactly("c1");
    }

    @Test
    void idleConversationsAreForgottenAfterTheTtl() {
        ExpiringChatMemoryRepository repo = new ExpiringChatMemoryRepository(Duration.ofMinutes(30), 100, clock);
        repo.saveAll("stale", List.of(new UserMessage("old")));
        clock.advance(Duration.ofMinutes(20));
        repo.saveAll("fresh", List.of(new UserMessage("new")));
        // Reading keeps a conversation alive.
        repo.findByConversationId("fresh");
        clock.advance(Duration.ofMinutes(15)); // stale idle 35 min, fresh idle 15 min

        assertThat(repo.findConversationIds()).containsExactly("fresh");
        assertThat(repo.findByConversationId("stale")).isEmpty();
    }

    @Test
    void capEvictsTheLeastRecentlyUsedConversation() {
        ExpiringChatMemoryRepository repo = new ExpiringChatMemoryRepository(Duration.ofHours(1), 2, clock);
        repo.saveAll("a", List.of(new UserMessage("a")));
        clock.advance(Duration.ofSeconds(1));
        repo.saveAll("b", List.of(new UserMessage("b")));
        clock.advance(Duration.ofSeconds(1));
        repo.findByConversationId("a"); // a is now more recent than b
        clock.advance(Duration.ofSeconds(1));
        repo.saveAll("c", List.of(new UserMessage("c")));

        assertThat(repo.size()).isEqualTo(2);
        assertThat(repo.findConversationIds()).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void deleteRemovesTheConversation() {
        ExpiringChatMemoryRepository repo = new ExpiringChatMemoryRepository(Duration.ofHours(1), 10, clock);
        repo.saveAll("c1", List.of(new UserMessage("x")));

        repo.deleteByConversationId("c1");

        assertThat(repo.findByConversationId("c1")).isEmpty();
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new ExpiringChatMemoryRepository(Duration.ZERO, 10, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpiringChatMemoryRepository(Duration.ofMinutes(1), 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
