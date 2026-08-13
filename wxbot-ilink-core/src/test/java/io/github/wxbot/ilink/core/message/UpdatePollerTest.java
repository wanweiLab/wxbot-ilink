/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.UpdateProtocol;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePollerTest {

    @Test
    void 应使用已提交游标并在持久化后分发() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryInboxStore inbox = new InMemoryInboxStore(clock);
        ConversationContextManager contexts = new ConversationContextManager(clock);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<String> receivedCursor = new AtomicReference<>();
        UpdateProtocol protocol = (session, cursor) -> {
            receivedCursor.set(cursor);
            return CompletableFuture.completedFuture(
                    new UpdateBatch("cursor-1", List.of(message(1L, "context-1"))));
        };
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(1, 4, inbox, delivery -> {
            handled.countDown();
            return CompletableFuture.completedFuture(null);
        });
        UpdatePoller poller = new UpdatePoller(
                "client", session(), protocol, inbox, contexts, dispatcher, clock);

        assertEquals(1, poller.pollOnce().toCompletableFuture().join());
        assertTrue(handled.await(1, TimeUnit.SECONDS));
        assertEquals("", receivedCursor.get());
        assertEquals("cursor-1", inbox.loadCursor("client").toCompletableFuture().join());
        assertEquals("context-1", contexts.find("user").orElseThrow().contextToken());
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void 同时只能存在一个拉取请求() {
        Clock clock = Clock.systemUTC();
        InMemoryInboxStore inbox = new InMemoryInboxStore(clock);
        CompletableFuture<UpdateBatch> pending = new CompletableFuture<>();
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, delivery -> CompletableFuture.completedFuture(null));
        UpdatePoller poller = new UpdatePoller(
                "client", session(), (session, cursor) -> pending, inbox,
                new ConversationContextManager(clock), dispatcher, clock);

        CompletableFuture<Integer> first = poller.pollOnce().toCompletableFuture();
        assertThrows(CompletionException.class, poller.pollOnce().toCompletableFuture()::join);
        pending.complete(new UpdateBatch("cursor", List.of()));
        assertEquals(0, first.join());
        dispatcher.close();
    }

    @Test
    void 关闭时应取消进行中的协议长轮询() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryInboxStore inbox = new InMemoryInboxStore(clock);
        CompletableFuture<UpdateBatch> pending = new CompletableFuture<>();
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, delivery -> CompletableFuture.completedFuture(null));
        UpdatePoller poller = new UpdatePoller(
                "client", session(), (session, cursor) -> pending, inbox,
                new ConversationContextManager(clock), dispatcher, clock);

        CompletableFuture<Integer> polling = poller.pollOnce().toCompletableFuture();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!pending.isCancelled() && System.nanoTime() < deadline) {
            poller.close();
            Thread.sleep(5L);
        }

        assertTrue(pending.isCancelled());
        CompletionException cancelled = assertThrows(CompletionException.class, polling::join);
        assertTrue(cancelled.getCause() instanceof java.util.concurrent.CancellationException);
        dispatcher.close();
    }

    private static BotSession session() {
        return new BotSession("token", "owner", "bot", URI.create("https://example.test"));
    }

    private static InboundMessage message(long id, String token) {
        return new InboundMessage(id, "user", "bot", Instant.now(), token, List.of());
    }
}
