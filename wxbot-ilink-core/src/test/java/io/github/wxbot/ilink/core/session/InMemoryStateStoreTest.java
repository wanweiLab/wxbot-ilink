/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStateStoreTest {

    @Test
    void 应保存加载并清除客户端快照() {
        InMemoryStateStore store = new InMemoryStateStore();
        ClientSnapshot snapshot = new ClientSnapshot(
                ClientSnapshot.CURRENT_SCHEMA_VERSION,
                new BotSession("token", "user", "bot", URI.create("https://example.test")),
                "cursor",
                Map.of(),
                Instant.parse("2026-08-12T00:00:00Z"));

        store.save("client-1", snapshot).toCompletableFuture().join();
        assertEquals(snapshot, store.load("client-1").toCompletableFuture().join().orElseThrow());

        store.clear("client-1").toCompletableFuture().join();
        assertTrue(store.load("client-1").toCompletableFuture().join().isEmpty());
    }
}
