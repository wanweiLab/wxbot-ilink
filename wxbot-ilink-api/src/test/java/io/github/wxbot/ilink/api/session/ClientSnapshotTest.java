/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSnapshotTest {

    @Test
    void 字符串表示不应泄漏敏感字段() {
        BotSession session = new BotSession(
                "secret-bot-token", "user-sensitive", "bot-sensitive", URI.create("https://example.test"));
        ConversationSnapshot conversation = new ConversationSnapshot(
                "user-sensitive", "secret-context-token", 12L,
                Instant.parse("2026-08-12T00:00:00Z"), Instant.parse("2026-08-12T00:00:01Z"));
        ClientSnapshot snapshot = new ClientSnapshot(
                ClientSnapshot.CURRENT_SCHEMA_VERSION,
                session,
                "secret-cursor",
                Map.of("user-sensitive", conversation),
                Instant.parse("2026-08-12T00:00:02Z"));

        String text = snapshot.toString();

        assertFalse(text.contains("secret-bot-token"));
        assertFalse(text.contains("secret-context-token"));
        assertFalse(text.contains("secret-cursor"));
        assertTrue(text.contains("conversationCount=1"));
    }

    @Test
    void 应拒绝无法识别的结构版本() {
        BotSession session = new BotSession(
                "token", "user", "bot", URI.create("https://example.test"));

        assertThrows(IllegalArgumentException.class,
                () -> new ClientSnapshot(99, session, "", Map.of(), Instant.now()));
    }
}
