/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import io.github.wxbot.ilink.core.lifecycle.ConnectionSupervisor;
import io.github.wxbot.ilink.core.state.ClientStateMachine;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateLoopTest {

    @Test
    void 分发队列饱和时应暂停网络拉取并在释放后恢复() throws Exception {
        Clock clock = Clock.systemUTC();
        InMemoryInboxStore inbox = new InMemoryInboxStore(clock);
        CountDownLatch block = new CountDownLatch(1);
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 1, inbox, delivery -> {
                    try {
                        block.await(1, TimeUnit.SECONDS);
                        return CompletableFuture.completedFuture(null);
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        return CompletableFuture.failedFuture(failure);
                    }
                });
        assertTrue(dispatcher.dispatch(stored(1L)));
        assertTrue(dispatcher.dispatch(stored(2L)));
        assertTrue(!dispatcher.hasCapacity());

        AtomicInteger protocolCalls = new AtomicInteger();
        UpdatePoller poller = new UpdatePoller(
                "client", session(), (session, cursor) -> {
                    protocolCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(new UpdateBatch(cursor, List.of()));
                }, inbox, new ConversationContextManager(clock), dispatcher, clock);
        ClientStateMachine states = new ClientStateMachine(clock, ignored -> { });
        states.transitionTo(ClientState.RESTORING, "测试恢复");
        states.transitionTo(ClientState.CONNECTED, "测试连接");
        ConnectionSupervisor supervisor = new ConnectionSupervisor(states, 2, 5, clock);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        UpdateLoop loop = new UpdateLoop(poller, supervisor, states, scheduler,
                Duration.ofMillis(20), Duration.ofMillis(100));

        loop.start();
        Thread.sleep(80L);
        assertEquals(0, protocolCalls.get());

        block.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (protocolCalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }

        loop.close();
        poller.close();
        dispatcher.close();
        scheduler.shutdownNow();
        assertTrue(protocolCalls.get() > 0);
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    }

    private static StoredMessage stored(long id) {
        return new StoredMessage("client", new InboundMessage(
                id, "user", "bot", Instant.now(), "token-" + id, List.of()),
                1, Instant.now());
    }

    private static BotSession session() {
        return new BotSession("token", "owner", "bot", URI.create("https://example.test"));
    }
}
