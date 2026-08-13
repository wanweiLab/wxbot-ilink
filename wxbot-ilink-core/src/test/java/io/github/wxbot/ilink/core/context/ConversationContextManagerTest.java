/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.context;

import io.github.wxbot.ilink.api.message.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationContextManagerTest {

    @Test
    void 旧消息不应覆盖新上下文() {
        ConversationContextManager manager = new ConversationContextManager(
                Clock.fixed(Instant.parse("2026-08-12T00:00:10Z"), ZoneOffset.UTC));

        assertTrue(manager.update(message(2L, "new-token", "2026-08-12T00:00:02Z")));
        assertFalse(manager.update(message(1L, "old-token", "2026-08-12T00:00:01Z")));

        assertEquals("new-token", manager.find("user-1").orElseThrow().contextToken());
    }

    @Test
    void 相同时间应使用更大消息标识() {
        ConversationContextManager manager = new ConversationContextManager(Clock.systemUTC());

        manager.update(message(1L, "first-token", "2026-08-12T00:00:01Z"));
        manager.update(message(2L, "second-token", "2026-08-12T00:00:01Z"));

        assertEquals("second-token", manager.find("user-1").orElseThrow().contextToken());
    }

    @Test
    void 应清理过期上下文并支持显式失效() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-12T00:00:00Z"));
        ConversationContextManager manager = new ConversationContextManager(clock, Duration.ofSeconds(5));
        manager.update(message(1L, "token", "2026-08-12T00:00:00Z"));

        assertTrue(manager.find("user-1").isPresent());
        assertTrue(manager.invalidate("user-1"));
        assertTrue(manager.find("user-1").isEmpty());

        manager.update(message(2L, "token-2", "2026-08-12T00:00:01Z"));
        clock.now = Instant.parse("2026-08-12T00:00:06Z");
        assertTrue(manager.find("user-1").isEmpty());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
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

    private static InboundMessage message(long id, String token, String time) {
        return new InboundMessage(id, "user-1", "bot-1", Instant.parse(time), token, List.of());
    }
}
