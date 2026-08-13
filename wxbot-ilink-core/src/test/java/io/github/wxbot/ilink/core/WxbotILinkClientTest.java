/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core;

import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.StateStore;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.core.message.InMemoryInboxStore;
import io.github.wxbot.ilink.core.session.InMemoryStateStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WxbotILinkClientTest {

    @Test
    void 应从快照恢复发送并关闭() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        BotSession session = session();
        stateStore.save("client", new ClientSnapshot(
                ClientSnapshot.CURRENT_SCHEMA_VERSION,
                session,
                "cursor",
                Map.of("user", new io.github.wxbot.ilink.api.session.ConversationSnapshot(
                        "user", "context", 1L, Instant.now(), Instant.now())),
                Instant.now())).toCompletableFuture().join();
        WxbotILinkClient client = client(stateStore);

        assertTrue(client.restore().toCompletableFuture().join());
        SendReceipt receipt = client.send(SendMessageRequest.text("client-id", "user", "你好"))
                .toCompletableFuture().join();

        assertEquals("client-id", receipt.clientId());
        assertEquals(ClientState.CONNECTED, client.state());
        client.close();
        assertEquals(ClientState.CLOSED, client.state());
        assertThrows(ClientClosedException.class,
                () -> client.send(SendMessageRequest.text("next", "user", "关闭后发送")));
        assertThrows(ClientClosedException.class, client::saveSnapshot);
    }

    @Test
    void 没有快照时应进入待登录状态() {
        WxbotILinkClient client = client(new InMemoryStateStore());

        assertFalse(client.restore().toCompletableFuture().join());
        assertEquals(ClientState.LOGIN_REQUIRED, client.state());

        client.close();
        assertEquals(ClientState.CLOSED, client.state());
    }

    @Test
    void 关闭应共享单一期限而非按组件重复等待() {
        InMemoryStateStore stateStore = new InMemoryStateStore();
        stateStore.save("client", new ClientSnapshot(
                ClientSnapshot.CURRENT_SCHEMA_VERSION, session(), "cursor", Map.of(), Instant.now()))
                .toCompletableFuture().join();
        CountDownLatch neverReleased = new CountDownLatch(1);
        WxbotILinkClient client = client(stateStore, Duration.ofMillis(100), delivery -> {
            try {
                neverReleased.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });
        assertTrue(client.restore().toCompletableFuture().join());

        long started = System.nanoTime();
        client.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 500L, "关闭耗时不应按多个组件重复累计：" + elapsedMillis);
        assertEquals(ClientState.CLOSED, client.state());
    }

    @Test
    void 关闭后延迟完成的恢复不得重新启动运行时() {
        CompletableFuture<Optional<ClientSnapshot>> loading = new CompletableFuture<>();
        StateStore delayedStore = new StateStore() {
            @Override
            public java.util.concurrent.CompletionStage<Optional<ClientSnapshot>> load(String clientKey) {
                return loading;
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> save(
                    String clientKey, ClientSnapshot snapshot) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> clear(String clientKey) {
                return CompletableFuture.completedFuture(null);
            }
        };
        WxbotILinkClient client = client(delayedStore, Duration.ofSeconds(1), null);
        CompletableFuture<Boolean> restoring = client.restore().toCompletableFuture();

        client.close();
        loading.complete(Optional.of(new ClientSnapshot(
                ClientSnapshot.CURRENT_SCHEMA_VERSION, session(), "cursor", Map.of(), Instant.now())));

        assertTrue(restoring.isCompletedExceptionally());
        assertEquals(ClientState.CLOSED, client.state());
        assertThrows(ClientClosedException.class,
                () -> client.send(SendMessageRequest.text("after-close", "user", "关闭后")));
    }

    private static WxbotILinkClient client(StateStore stateStore) {
        return client(stateStore, Duration.ofSeconds(1), null);
    }

    private static WxbotILinkClient client(
            StateStore stateStore,
            Duration closeTimeout,
            io.github.wxbot.ilink.api.message.MessageHandler handler) {
        Clock clock = Clock.systemUTC();
        LoginProtocol login = new LoginProtocol() {
            @Override
            public CompletableFuture<QrCode> requestQrCode() {
                return CompletableFuture.completedFuture(
                        new QrCode("qr", "content", Instant.now().plusSeconds(10)));
            }

            @Override
            public CompletableFuture<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
                return CompletableFuture.completedFuture(LoginPollResult.confirmed(session()));
            }
        };
        MessageProtocol message = new MessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                return CompletableFuture.completedFuture(
                        new SendReceipt(request.clientId(), "server", Instant.now()));
            }

            @Override
            public CompletableFuture<String> requestTypingTicket(
                    BotSession session, String userId, String contextToken) {
                return CompletableFuture.completedFuture("ticket");
            }

            @Override
            public CompletableFuture<Void> setTyping(
                    BotSession session, String userId, String ticket, boolean typing) {
                return CompletableFuture.completedFuture(null);
            }
        };
        WxbotILinkClient.Builder builder = WxbotILinkClient.builder()
                .clientKey("client")
                .config(ILinkClientConfig.builder()
                        .dispatchStripes(1)
                        .dispatchQueueCapacity(8)
                        .closeTimeout(closeTimeout)
                        .build())
                .loginProtocol(login)
                .updateProtocol((session, cursor) -> new CompletableFuture<>())
                .messageProtocol(message)
                .stateStore(stateStore)
                .inboxStore(new InMemoryInboxStore(clock))
                .clock(clock);
        if (handler != null) {
            builder.messageHandler(handler);
        }
        return builder.build();
    }

    private static BotSession session() {
        return new BotSession("token", "owner", "bot", URI.create("https://example.test"));
    }
}
