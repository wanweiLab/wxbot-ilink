/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.store.jdbc;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.MessageItem;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcILinkStoreTest {

    private JdbcDataSource dataSource;
    private JdbcILinkStore store;

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:wxbot-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        store = new JdbcILinkStore(dataSource,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void shouldEncryptAndRestoreSnapshotAcrossStoreInstances() throws Exception {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ConversationSnapshot conversation = new ConversationSnapshot(
                "user-secret", "context-secret", 9, now, now);
        ClientSnapshot snapshot = new ClientSnapshot(1,
                new BotSession("token-secret", "user-secret", "bot-secret",
                        URI.create("https://example.test")),
                "cursor-secret", Map.of("user-secret", conversation), now);
        store.save("client-A", snapshot).toCompletableFuture().join();

        try (JdbcILinkStore reopened = new JdbcILinkStore(dataSource,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))) {
            assertEquals(snapshot, reopened.load("client-A").toCompletableFuture().join().orElseThrow());
        }

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT payload FROM wxbot_ilink_snapshot")) {
            assertTrue(result.next());
            String stored = new String(result.getBytes(1), StandardCharsets.ISO_8859_1);
            assertFalse(stored.contains("token-secret"));
            assertFalse(stored.contains("context-secret"));
        }
    }

    @Test
    void shouldAtomicallyAdvanceCursorDeduplicateAndRecoverClaims() {
        InboundMessage message = message(1, Instant.parse("2026-01-01T00:00:00Z"));
        var first = store.persistBatch("client-A", "",
                new UpdateBatch("cursor-1", List.of(message)), Duration.ofSeconds(5))
                .toCompletableFuture().join();
        assertEquals(1, first.acceptedMessages().size());
        assertEquals("cursor-1", store.loadCursor("client-A").toCompletableFuture().join());

        var duplicate = store.persistBatch("client-A", "cursor-1",
                new UpdateBatch("cursor-2", List.of(message)), Duration.ofSeconds(5))
                .toCompletableFuture().join();
        assertTrue(duplicate.acceptedMessages().isEmpty());

        assertTrue(store.claimPending("client-A", Instant.now(), 10, Duration.ofMinutes(1))
                .toCompletableFuture().join().isEmpty());
        var recovered = store.claimPending("client-A", Instant.now().plusSeconds(10), 10,
                Duration.ofMinutes(1)).toCompletableFuture().join();
        assertEquals(1, recovered.size());
        store.acknowledge("client-A", 1).toCompletableFuture().join();
        assertTrue(store.claimPending("client-A", Instant.now().plusSeconds(120), 10,
                Duration.ofMinutes(1)).toCompletableFuture().join().isEmpty());
    }

    @Test
    void shouldRejectStaleCursorWithoutSavingMessages() {
        store.persistBatch("client-A", "",
                new UpdateBatch("cursor-1", List.of(message(1, Instant.now()))), Duration.ofSeconds(1))
                .toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> store.persistBatch("client-A", "",
                new UpdateBatch("cursor-stale", List.of(message(2, Instant.now()))), Duration.ofSeconds(1))
                .toCompletableFuture().join());
        assertEquals("cursor-1", store.loadCursor("client-A").toCompletableFuture().join());
        assertEquals(1, store.claimPending("client-A", Instant.now().plusSeconds(2), 10,
                Duration.ofSeconds(1)).toCompletableFuture().join().size());
    }

    @Test
    void shouldPersistRetryAttemptAndDelay() {
        store.persistBatch("client-A", "",
                new UpdateBatch("cursor-1", List.of(message(1, Instant.now()))), Duration.ofMillis(1))
                .toCompletableFuture().join();
        store.markForRetry("client-A", 1, Duration.ofHours(1), "业务失败")
                .toCompletableFuture().join();
        assertTrue(store.claimPending("client-A", Instant.now().plusSeconds(10), 10,
                Duration.ofSeconds(1)).toCompletableFuture().join().isEmpty());
        var retry = store.claimPending("client-A", Instant.now().plusSeconds(3700), 10,
                Duration.ofSeconds(1)).toCompletableFuture().join();
        assertEquals(2, retry.get(0).attempt());
    }

    @Test
    void 应持久化死信并从积压中排除() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        store.persistBatch("client-A", "",
                new UpdateBatch("cursor-1", List.of(message(7, now))), Duration.ofSeconds(1))
                .toCompletableFuture().join();
        assertEquals(1L, store.countPending("client-A").toCompletableFuture().join());

        store.deadLetter("client-A", 7, "最终失败", now.plusSeconds(1))
                .toCompletableFuture().join();

        assertEquals(0L, store.countPending("client-A").toCompletableFuture().join());
        var deadLetters = store.loadDeadLetters("client-A", 10).toCompletableFuture().join();
        assertEquals(1, deadLetters.size());
        assertEquals(7L, deadLetters.get(0).message().messageId());
        assertEquals("最终失败", deadLetters.get(0).reason());
    }

    @Test
    void shouldAllowOnlyOneOwnerAndRecoverExpiredLease() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertTrue(store.tryAcquire("client-A", "node-1", now, Duration.ofSeconds(30))
                .toCompletableFuture().join());
        assertFalse(store.tryAcquire("client-A", "node-2", now.plusSeconds(10), Duration.ofSeconds(30))
                .toCompletableFuture().join());
        assertTrue(store.renew("client-A", "node-1", now.plusSeconds(10), Duration.ofSeconds(30))
                .toCompletableFuture().join());
        assertFalse(store.renew("client-A", "node-2", now.plusSeconds(10), Duration.ofSeconds(30))
                .toCompletableFuture().join());
        assertTrue(store.tryAcquire("client-A", "node-2", now.plusSeconds(41), Duration.ofSeconds(30))
                .toCompletableFuture().join());
        store.release("client-A", "node-2").toCompletableFuture().join();
        assertTrue(store.tryAcquire("client-A", "node-1", now.plusSeconds(42), Duration.ofSeconds(30))
                .toCompletableFuture().join());
    }

    @Test
    void 失租实例不得提交消息和推进游标() {
        Instant now = Instant.parse("2026-08-12T00:00:00Z");
        assertTrue(store.tryAcquire("client-A", "node-1", now, Duration.ofSeconds(30))
                .toCompletableFuture().join());
        assertTrue(store.tryAcquire("client-A", "node-2", now.plusSeconds(31), Duration.ofSeconds(30))
                .toCompletableFuture().join());

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> store.persistBatchWhileLeaseHeld(
                                "client-A",
                                "node-1",
                                now.plusSeconds(32),
                                "",
                                new UpdateBatch("cursor-stale", List.of(message(9, now.plusSeconds(32)))),
                                Duration.ofMinutes(1))
                        .toCompletableFuture().join());

        assertEquals("", store.loadCursor("client-A").toCompletableFuture().join());
    }

    @Test
    void 应为旧版收件箱结构补充死信字段() throws Exception {
        JdbcDataSource legacyDataSource = new JdbcDataSource();
        legacyDataSource.setURL("jdbc:h2:mem:wxbot-legacy-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = legacyDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE wxbot_ilink_inbox ("
                    + "client_key VARCHAR(255) NOT NULL, message_id BIGINT NOT NULL, payload BLOB NOT NULL, "
                    + "created_at BIGINT NOT NULL, attempt INT NOT NULL, available_at BIGINT NOT NULL, "
                    + "claimed_until BIGINT, acknowledged BOOLEAN NOT NULL, last_error VARCHAR(255), "
                    + "PRIMARY KEY (client_key, message_id))");
        }

        try (JdbcILinkStore migrated = new JdbcILinkStore(legacyDataSource,
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
             Connection connection = legacyDataSource.getConnection()) {
            boolean deadLetter = false;
            boolean failedAt = false;
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, "WXBOT_ILINK_INBOX", null)) {
                while (columns.next()) {
                    deadLetter |= "DEAD_LETTER".equalsIgnoreCase(columns.getString("COLUMN_NAME"));
                    failedAt |= "FAILED_AT".equalsIgnoreCase(columns.getString("COLUMN_NAME"));
                }
            }
            assertTrue(deadLetter);
            assertTrue(failedAt);
        }
    }

    private static InboundMessage message(long id, Instant createdAt) {
        return new InboundMessage(id, "user", "bot", createdAt, "context-" + id,
                List.of(new MessageItem(99, Map.of("unknown", List.of(1, 2, 3)))));
    }
}
