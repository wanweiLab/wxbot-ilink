/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.PersistedBatch;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryInboxStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Test
    void 应原子去重并推进游标() {
        InMemoryInboxStore store = store();
        UpdateBatch first = new UpdateBatch("cursor-1", List.of(message(1L), message(1L)));

        PersistedBatch persisted = store.persistBatch(
                "client", "", first, Duration.ofMinutes(1)).toCompletableFuture().join();

        assertEquals("cursor-1", persisted.cursor());
        assertEquals(1, persisted.acceptedMessages().size());
        assertEquals("cursor-1", store.loadCursor("client").toCompletableFuture().join());
    }

    @Test
    void 应拒绝过期游标覆盖新游标() {
        InMemoryInboxStore store = store();
        store.persistBatch("client", "", new UpdateBatch("cursor-1", List.of()), Duration.ofMinutes(1))
                .toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> store.persistBatch(
                                "client", "", new UpdateBatch("cursor-2", List.of()), Duration.ofMinutes(1))
                        .toCompletableFuture().join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    @Test
    void 消息领取期间不应重复返回() {
        InMemoryInboxStore store = store();
        store.persistBatch("client", "", new UpdateBatch("cursor-1", List.of(message(1L))),
                Duration.ofSeconds(1)).toCompletableFuture().join();

        assertTrue(store.claimPending("client", NOW, 10, Duration.ofSeconds(1))
                .toCompletableFuture().join().isEmpty());
        assertEquals(1, store.claimPending("client", NOW.plusSeconds(2), 10, Duration.ofSeconds(1))
                .toCompletableFuture().join().size());
    }

    private static InMemoryInboxStore store() {
        return new InMemoryInboxStore(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InboundMessage message(long id) {
        return new InboundMessage(id, "user-1", "bot-1", NOW, "token", List.of());
    }
}
