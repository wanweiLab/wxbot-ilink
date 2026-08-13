/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStateStoreTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    @Test
    void 应加密保存并在新实例中完整恢复() throws Exception {
        ClientSnapshot expected = snapshot("secret-token", "cursor-1");
        try (FileStateStore store = new FileStateStore(directory, KEY)) {
            store.save("client-a", expected).toCompletableFuture().join();
        }

        byte[] file = Files.readAllBytes(snapshotFile());
        String raw = new String(file, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("secret-token"));
        assertFalse(raw.contains("context-secret"));

        try (FileStateStore reopened = new FileStateStore(directory, KEY)) {
            assertEquals(expected, reopened.load("client-a").toCompletableFuture().join().orElseThrow());
        }
    }

    @Test
    void 损坏密文时应拒绝恢复且不返回部分数据() throws Exception {
        try (FileStateStore store = new FileStateStore(directory, KEY)) {
            store.save("client-a", snapshot("secret-token", "cursor-1"))
                    .toCompletableFuture().join();
        }
        Path file = snapshotFile();
        byte[] corrupted = Files.readAllBytes(file);
        corrupted[corrupted.length - 1] ^= 1;
        Files.write(file, corrupted);

        try (FileStateStore store = new FileStateStore(directory, KEY)) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> store.load("client-a").toCompletableFuture().join());
            assertTrue(failure.getCause().getMessage().contains("认证失败"));
        }
    }

    @Test
    void 保存任务被拒绝时应保留旧快照() {
        ClientSnapshot original = snapshot("token-old", "cursor-old");
        FileStateStore closed = new FileStateStore(directory, KEY);
        closed.save("client-a", original).toCompletableFuture().join();
        closed.close();

        assertThrows(CompletionException.class, () -> closed.save(
                "client-a", snapshot("token-new", "cursor-new")).toCompletableFuture().join());

        try (FileStateStore reopened = new FileStateStore(directory, KEY)) {
            assertEquals(original, reopened.load("client-a").toCompletableFuture().join().orElseThrow());
        }
    }

    @Test
    void 清除后应返回空结果() {
        try (FileStateStore store = new FileStateStore(directory, KEY)) {
            store.save("client-a", snapshot("token", "cursor")).toCompletableFuture().join();
            store.clear("client-a").toCompletableFuture().join();
            assertTrue(store.load("client-a").toCompletableFuture().join().isEmpty());
        }
    }

    private Path snapshotFile() throws Exception {
        return Files.list(directory)
                .filter(path -> path.getFileName().toString().endsWith(".snapshot"))
                .findFirst().orElseThrow();
    }

    private static ClientSnapshot snapshot(String token, String cursor) {
        Instant time = Instant.parse("2026-08-12T06:00:00Z");
        ConversationSnapshot conversation = new ConversationSnapshot(
                "user-a", "context-secret", 42L, time.minusSeconds(1), time);
        return new ClientSnapshot(ClientSnapshot.CURRENT_SCHEMA_VERSION,
                new BotSession(token, "owner", "bot", URI.create("https://example.test/")),
                cursor, Map.of(conversation.userId(), conversation), time);
    }
}
