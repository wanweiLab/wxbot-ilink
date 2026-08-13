/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.exception.ContextMissingException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.ContextReference;
import io.github.wxbot.ilink.api.message.OutboundMessageType;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import io.github.wxbot.ilink.core.retry.AsyncRetryExecutor;
import io.github.wxbot.ilink.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSenderTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void 关闭调度器() throws InterruptedException {
        scheduler.shutdownNow();
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void 重试时应复用客户端幂等标识和解析后的上下文() {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context-token", List.of()));
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> clientId = new AtomicReference<>();
        AtomicReference<String> context = new AtomicReference<>();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                clientId.compareAndSet(null, request.clientId());
                assertEquals(clientId.get(), request.clientId());
                context.set(request.context().value());
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(
                            new TransportException("ILINK-NET-001", "临时失败", true, null));
                }
                return CompletableFuture.completedFuture(
                        new SendReceipt(request.clientId(), "server-1", Instant.now()));
            }
        };
        MessageSender sender = sender(protocol, contexts);

        SendReceipt receipt = sender.send(SendMessageRequest.text("client-1", "user", "你好"))
                .toCompletableFuture().join();

        assertEquals("client-1", receipt.clientId());
        assertEquals(2, attempts.get());
        assertEquals("context-token", context.get());
    }

    @Test
    void 缺少上下文应在发起协议请求前失败() {
        MessageSender sender = sender(new FakeMessageProtocol(), contexts());

        assertThrows(ContextMissingException.class,
                () -> sender.send(SendMessageRequest.text("client-1", "unknown", "你好")));
    }

    @Test
    void 带输入态发送应开始并最终停止输入态() {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context-token", List.of()));
        AtomicInteger typingChanges = new AtomicInteger();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<Void> setTyping(
                    BotSession session, String userId, String ticket, boolean typing) {
                typingChanges.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        MessageSender sender = sender(protocol, contexts);

        SendReceipt receipt = sender.sendWithTyping(
                SendMessageRequest.text("client-1", "user", "你好"), Duration.ofMillis(5))
                .toCompletableFuture().join();

        assertEquals("client-1", receipt.clientId());
        assertEquals(2, typingChanges.get());
    }

    @Test
    void 应使用指定来源消息的上下文而非最新上下文() {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now().minusSeconds(1), "context-old", List.of()));
        contexts.update(new InboundMessage(
                2L, "user", "bot", Instant.now(), "context-new", List.of()));
        AtomicReference<String> context = new AtomicReference<>();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                context.set(request.context().value());
                return super.send(session, request);
            }
        };

        sender(protocol, contexts).send(new SendMessageRequest(
                "client", "user", OutboundMessageType.TEXT,
                ContextReference.fromMessage(1L), Map.of("text", "回复")))
                .toCompletableFuture().join();

        assertEquals("context-old", context.get());
    }

    @Test
    void 同一用户并发发送应串行且不同用户可以并行() throws Exception {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user-a", "bot", Instant.now(), "context-a", List.of()));
        contexts.update(new InboundMessage(
                2L, "user-b", "bot", Instant.now(), "context-b", List.of()));
        CompletableFuture<SendReceipt> firstResult = new CompletableFuture<>();
        CountDownLatch userBStarted = new CountDownLatch(1);
        CopyOnWriteArrayList<String> started = new CopyOnWriteArrayList<>();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                started.add(request.clientId());
                if (request.clientId().equals("a-1")) {
                    return firstResult;
                }
                if (request.clientId().equals("b-1")) {
                    userBStarted.countDown();
                }
                return CompletableFuture.completedFuture(
                        new SendReceipt(request.clientId(), "server", Instant.now()));
            }
        };
        MessageSender sender = sender(protocol, contexts);

        CompletableFuture<SendReceipt> first = sender.send(
                SendMessageRequest.text("a-1", "user-a", "第一条")).toCompletableFuture();
        CompletableFuture<SendReceipt> second = sender.send(
                SendMessageRequest.text("a-2", "user-a", "第二条")).toCompletableFuture();
        CompletableFuture<SendReceipt> other = sender.send(
                SendMessageRequest.text("b-1", "user-b", "并行消息")).toCompletableFuture();

        assertTrue(userBStarted.await(1, TimeUnit.SECONDS));
        assertEquals(List.of("a-1", "b-1"), started);
        assertTrue(!second.isDone());
        firstResult.complete(new SendReceipt("a-1", "server-a-1", Instant.now()));
        assertEquals("a-1", first.join().clientId());
        assertEquals("a-2", second.join().clientId());
        assertEquals("b-1", other.join().clientId());
        assertEquals(List.of("a-1", "b-1", "a-2"), started);
    }

    @Test
    void 取消发送应传播到协议阶段并允许队列继续() throws Exception {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context", List.of()));
        CompletableFuture<SendReceipt> protocolCall = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                attempts.incrementAndGet();
                return protocolCall;
            }
        };
        MessageSender sender = sender(protocol, contexts);
        CompletableFuture<SendReceipt> active = sender.send(
                SendMessageRequest.text("first", "user", "第一条")).toCompletableFuture();
        assertTrue(active.cancel(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!protocolCall.isCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(protocolCall.isCancelled());
        assertEquals(1, attempts.get());
        sender.close();
    }

    @Test
    void 关闭发送器应取消所有在途和排队请求() {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context", List.of()));
        CopyOnWriteArrayList<CompletableFuture<SendReceipt>> calls = new CopyOnWriteArrayList<>();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                CompletableFuture<SendReceipt> call = new CompletableFuture<>();
                calls.add(call);
                return call;
            }
        };
        MessageSender sender = sender(protocol, contexts);
        CompletableFuture<SendReceipt> active = sender.send(
                SendMessageRequest.text("first", "user", "第一条")).toCompletableFuture();
        CompletableFuture<SendReceipt> queued = sender.send(
                SendMessageRequest.text("second", "user", "第二条")).toCompletableFuture();

        sender.close();

        assertTrue(active.isCancelled());
        assertTrue(queued.isCancelled());
        assertTrue(calls.stream().allMatch(CompletableFuture::isCancelled));
        assertTrue(sender.send(SendMessageRequest.text("third", "user", "关闭后"))
                .toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void 取消输入态发送应停止输入态且不得继续发送消息() throws Exception {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context", List.of()));
        CountDownLatch typingStarted = new CountDownLatch(1);
        CountDownLatch typingStopped = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<Void> setTyping(
                    BotSession session, String userId, String ticket, boolean typing) {
                (typing ? typingStarted : typingStopped).countDown();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                sends.incrementAndGet();
                return super.send(session, request);
            }
        };
        MessageSender sender = sender(protocol, contexts);
        CompletableFuture<SendReceipt> sending = sender.sendWithTyping(
                SendMessageRequest.text("client", "user", "取消"), Duration.ofSeconds(10))
                .toCompletableFuture();
        assertTrue(typingStarted.await(1, TimeUnit.SECONDS));

        assertTrue(sending.cancel(true));

        assertTrue(typingStopped.await(1, TimeUnit.SECONDS));
        assertEquals(0, sends.get());
        assertThrows(CancellationException.class, sending::join);
        sender.close();
    }

    @Test
    void 输入态启动请求未决时取消应等待结果后再停止输入态() throws Exception {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context", List.of()));
        CompletableFuture<Void> startingTyping = new CompletableFuture<>();
        CountDownLatch stopCalled = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        MessageProtocol protocol = new FakeMessageProtocol() {
            @Override
            public CompletableFuture<Void> setTyping(
                    BotSession session, String userId, String ticket, boolean typing) {
                if (typing) {
                    return startingTyping;
                }
                stopCalled.countDown();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<SendReceipt> send(BotSession session, SendMessageRequest request) {
                sends.incrementAndGet();
                return super.send(session, request);
            }
        };
        MessageSender sender = sender(protocol, contexts);
        CompletableFuture<SendReceipt> sending = sender.sendWithTyping(
                SendMessageRequest.text("client", "user", "取消"), Duration.ofSeconds(10))
                .toCompletableFuture();

        assertTrue(sending.cancel(true));
        assertTrue(!startingTyping.isCancelled());
        startingTyping.complete(null);

        assertTrue(stopCalled.await(1, TimeUnit.SECONDS));
        assertEquals(0, sends.get());
        sender.close();
    }

    @Test
    void 同步完成发送后不应遗留用户尾节点() throws Exception {
        ConversationContextManager contexts = contexts();
        contexts.update(new InboundMessage(
                1L, "user", "bot", Instant.now(), "context", List.of()));
        MessageSender sender = sender(new FakeMessageProtocol(), contexts);

        for (int index = 0; index < 100; index++) {
            sender.send(SendMessageRequest.text("client-" + index, "user", "同步完成"))
                    .toCompletableFuture().join();
        }

        Field tailsField = MessageSender.class.getDeclaredField("userSendTails");
        tailsField.setAccessible(true);
        Map<?, ?> tails = (Map<?, ?>) tailsField.get(sender);
        assertTrue(tails.isEmpty());
        sender.close();
    }

    private MessageSender sender(MessageProtocol protocol, ConversationContextManager contexts) {
        return new MessageSender(session(), protocol, contexts,
                new AsyncRetryExecutor(
                        new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(1)), scheduler),
                scheduler);
    }

    private static ConversationContextManager contexts() {
        return new ConversationContextManager(Clock.systemUTC());
    }

    private static BotSession session() {
        return new BotSession("token", "owner", "bot", URI.create("https://example.test"));
    }

    private static class FakeMessageProtocol implements MessageProtocol {
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
    }
}
