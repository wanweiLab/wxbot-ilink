/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.message.AcknowledgementMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StripedMessageDispatcherTest {

    @Test
    void 同一用户消息应严格有序() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        List<Long> order = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(3);
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(2, 8, inbox, delivery -> {
            order.add(delivery.message().messageId());
            completed.countDown();
            return CompletableFuture.completedFuture(null);
        });

        assertTrue(dispatcher.dispatch(stored(1L, "same-user")));
        assertTrue(dispatcher.dispatch(stored(2L, "same-user")));
        assertTrue(dispatcher.dispatch(stored(3L, "same-user")));
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        dispatcher.close();

        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
        assertEquals(List.of(1L, 2L, 3L), order);
    }

    @Test
    void 队列满时应拒绝而不是无限堆积() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        CountDownLatch block = new CountDownLatch(1);
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(1, 1, inbox, delivery -> {
            try {
                block.await(1, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(null);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(failure);
            }
        });

        assertTrue(dispatcher.dispatch(stored(1L, "same-user")));
        assertTrue(dispatcher.dispatch(stored(2L, "same-user")));
        assertFalse(dispatcher.dispatch(stored(3L, "same-user")));
        assertFalse(dispatcher.hasCapacity());

        block.countDown();
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void 处理超时达到上限时应进入死信() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        InboundMessage message = new InboundMessage(
                9L, "user", "bot", Instant.now(), "token", List.of());
        inbox.persistBatch("client", "", new io.github.wxbot.ilink.api.message.UpdateBatch(
                "cursor", List.of(message)), Duration.ofMillis(1)).toCompletableFuture().join();
        Thread.sleep(5L);
        StoredMessage stored = inbox.claimPending(
                "client", Instant.now(), 1, Duration.ofMinutes(1))
                .toCompletableFuture().join().get(0);
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, delivery -> new CompletableFuture<>(),
                Duration.ofMillis(30), 1, AcknowledgementMode.AUTO);

        assertTrue(dispatcher.dispatch(stored));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (inbox.loadDeadLetters("client", 10).toCompletableFuture().join().isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        dispatcher.close();

        assertEquals(1, inbox.loadDeadLetters("client", 10).toCompletableFuture().join().size());
        assertEquals(0L, inbox.countPending("client").toCompletableFuture().join());
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void 处理超时时应取消业务阶段() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        InboundMessage message = new InboundMessage(
                11L, "user", "bot", Instant.now(), "token", List.of());
        inbox.persistBatch("client", "", new io.github.wxbot.ilink.api.message.UpdateBatch(
                "cursor", List.of(message)), Duration.ofMillis(1)).toCompletableFuture().join();
        Thread.sleep(5L);
        StoredMessage stored = inbox.claimPending(
                "client", Instant.now(), 1, Duration.ofMinutes(1))
                .toCompletableFuture().join().get(0);
        CompletableFuture<Void> handler = new CompletableFuture<>();
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, delivery -> handler,
                Duration.ofMillis(30), 2, AcknowledgementMode.AUTO);

        assertTrue(dispatcher.dispatch(stored));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!handler.isCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        dispatcher.close();

        assertTrue(handler.isCancelled());
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void 手动确认模式不得自动确认成功阶段() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        InboundMessage message = new InboundMessage(
                10L, "user", "bot", Instant.now(), "token", List.of());
        inbox.persistBatch("client", "", new io.github.wxbot.ilink.api.message.UpdateBatch(
                "cursor", List.of(message)), Duration.ofMillis(1)).toCompletableFuture().join();
        Thread.sleep(5L);
        StoredMessage stored = inbox.claimPending(
                "client", Instant.now(), 1, Duration.ofMillis(30))
                .toCompletableFuture().join().get(0);
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, delivery -> CompletableFuture.completedFuture(null),
                Duration.ofSeconds(1), 3, AcknowledgementMode.MANUAL);

        assertTrue(dispatcher.dispatch(stored));
        Thread.sleep(80L);
        dispatcher.close();

        assertEquals(1L, inbox.countPending("client").toCompletableFuture().join());
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    private static StoredMessage stored(long id, String userId) {
        InboundMessage message = new InboundMessage(
                id, userId, "bot", Instant.now(), "token-" + id, List.of());
        return new StoredMessage("client", message, 1, Instant.now());
    }
}
