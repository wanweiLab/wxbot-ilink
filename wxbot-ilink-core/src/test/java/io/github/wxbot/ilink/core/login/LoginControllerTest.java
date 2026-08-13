/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.login;

import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.api.exception.LoginTimeoutException;
import io.github.wxbot.ilink.api.exception.QrCodeExpiredException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.core.state.ClientStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginControllerTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void 关闭调度器() throws InterruptedException {
        scheduler.shutdownNow();
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void 应按等待扫码确认顺序完成登录() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(LoginPollResult.waiting());
        protocol.results.add(LoginPollResult.scanned());
        protocol.results.add(LoginPollResult.confirmed(session()));
        ClientStateMachine stateMachine = new ClientStateMachine();
        LoginController controller = controller(protocol, stateMachine);

        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);
        BotSession session = attempt.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals("bot-1", session.botId());
        assertEquals(ClientState.CONNECTED, stateMachine.current());
        assertEquals(3, protocol.pollCount.get());
        controller.close();
    }

    @Test
    void 取消后不应继续查询状态() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(LoginPollResult.waiting());
        protocol.results.add(LoginPollResult.waiting());
        ClientStateMachine stateMachine = new ClientStateMachine();
        LoginController controller = controller(protocol, stateMachine);
        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);

        waitUntil(() -> protocol.pollCount.get() >= 1);
        assertTrue(attempt.cancel());
        int pollsAfterCancel = protocol.pollCount.get();
        Thread.sleep(40L);

        assertEquals(pollsAfterCancel, protocol.pollCount.get());
        assertEquals(ClientState.LOGIN_REQUIRED, stateMachine.current());
        assertThrows(CancellationException.class, attempt.completion().toCompletableFuture()::join);
        controller.close();
    }

    @Test
    void 二维码过期应进入过期状态() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(LoginPollResult.expired());
        ClientStateMachine stateMachine = new ClientStateMachine();
        LoginController controller = controller(protocol, stateMachine);
        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);

        CompletionException failure = assertThrows(
                CompletionException.class, attempt.completion().toCompletableFuture()::join);

        assertInstanceOf(QrCodeExpiredException.class, failure.getCause());
        assertEquals(ClientState.EXPIRED, stateMachine.current());
        controller.close();
    }

    @Test
    void 可重试的状态查询异常不应终止登录() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(new TransportException(
                "ILINK-NET-001", "模拟读超时", true, null));
        protocol.results.add(LoginPollResult.confirmed(session()));
        ClientStateMachine stateMachine = new ClientStateMachine();
        LoginController controller = controller(protocol, stateMachine);

        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);
        waitUntil(() -> protocol.pollCount.get() >= 2);
        BotSession result = attempt.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertEquals("bot-1", result.botId());
        assertEquals(2, protocol.pollCount.get());
        assertEquals(ClientState.CONNECTED, stateMachine.current());
        controller.close();
    }

    @Test
    void 不可重试的状态查询异常应立即终止登录() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        TransportException expected = new TransportException(
                "ILINK-HTTP-401", "模拟认证失败", false, null);
        protocol.results.add(expected);
        ClientStateMachine stateMachine = new ClientStateMachine();
        LoginController controller = controller(protocol, stateMachine);
        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);

        CompletionException failure = assertThrows(
                CompletionException.class, attempt.completion().toCompletableFuture()::join);

        assertEquals(expected, failure.getCause());
        assertEquals(1, protocol.pollCount.get());
        assertEquals(ClientState.LOGIN_REQUIRED, stateMachine.current());
        controller.close();
    }

    @Test
    void 取消后不应执行已经安排的异常重试() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(new TransportException(
                "ILINK-NET-001", "模拟读超时", true, null));
        LoginController controller = controller(
                protocol, new ClientStateMachine(), Duration.ofMillis(200), Duration.ofSeconds(1));
        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);
        waitUntil(() -> protocol.pollCount.get() == 1);

        assertTrue(attempt.cancel());
        Thread.sleep(250L);

        assertEquals(1, protocol.pollCount.get());
        controller.close();
    }

    @Test
    void 可重试异常仍应受登录总时限约束() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        for (int index = 0; index < 10; index++) {
            protocol.results.add(new TransportException(
                    "ILINK-NET-001", "模拟持续读超时", true, null));
        }
        LoginController controller = controller(
                protocol, new ClientStateMachine(), Duration.ofMillis(10), Duration.ofMillis(80));
        LoginAttempt attempt = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);

        CompletionException failure = assertThrows(
                CompletionException.class, attempt.completion().toCompletableFuture()::join);

        assertInstanceOf(LoginTimeoutException.class, failure.getCause());
        assertTrue(protocol.pollCount.get() > 1);
        controller.close();
    }

    @Test
    void 同时只能存在一个登录任务() throws Exception {
        FakeLoginProtocol protocol = new FakeLoginProtocol();
        protocol.results.add(LoginPollResult.waiting());
        LoginController controller = controller(protocol, new ClientStateMachine());
        LoginAttempt first = controller.start().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertThrows(CompletionException.class, controller.start().toCompletableFuture()::join);

        first.cancel();
        controller.close();
    }

    @Test
    void 取消时应取消正在执行的协议请求() throws Exception {
        CompletableFuture<LoginPollResult> inFlight = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        LoginProtocol protocol = new LoginProtocol() {
            @Override
            public CompletableFuture<QrCode> requestQrCode() {
                return CompletableFuture.completedFuture(new QrCode(
                        "qr-token", "qr-content", Instant.now().plusSeconds(10)));
            }

            @Override
            public CompletableFuture<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
                calls.incrementAndGet();
                return inFlight;
            }
        };
        LoginController controller = controller(protocol, new ClientStateMachine());
        LoginAttempt attempt = controller.start().toCompletableFuture().join();
        waitUntil(() -> calls.get() == 1);

        attempt.cancel();

        assertTrue(inFlight.isCancelled());
        controller.close();
    }

    @Test
    void 关闭时应取消尚未返回的二维码请求() {
        CompletableFuture<QrCode> request = new CompletableFuture<>();
        LoginProtocol protocol = new LoginProtocol() {
            @Override
            public CompletableFuture<QrCode> requestQrCode() {
                return request;
            }

            @Override
            public CompletableFuture<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
                throw new AssertionError("关闭后不应开始轮询");
            }
        };
        LoginController controller = controller(protocol, new ClientStateMachine());
        CompletableFuture<LoginAttempt> starting = controller.start().toCompletableFuture();

        controller.close();

        assertTrue(request.isCancelled());
        assertTrue(starting.isCompletedExceptionally());
        assertThrows(CompletionException.class, starting::join);
        assertTrue(controller.start().toCompletableFuture().isCompletedExceptionally());
    }

    private LoginController controller(LoginProtocol protocol, ClientStateMachine stateMachine) {
        return controller(protocol, stateMachine, Duration.ofMillis(10), Duration.ofSeconds(1));
    }

    private LoginController controller(
            LoginProtocol protocol,
            ClientStateMachine stateMachine,
            Duration pollInterval,
            Duration loginTimeout) {
        ILinkClientConfig config = ILinkClientConfig.builder()
                .loginPollInterval(pollInterval)
                .loginTimeout(loginTimeout)
                .build();
        return new LoginController(config, protocol, stateMachine, scheduler, Clock.systemUTC());
    }

    private static BotSession session() {
        return new BotSession("token", "user-1", "bot-1", URI.create("https://example.test"));
    }

    private static void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!check.done() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(check.done(), "等待条件超时");
    }

    @FunctionalInterface
    private interface Check {
        boolean done();
    }

    private static final class FakeLoginProtocol implements LoginProtocol {
        private final Queue<Object> results = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pollCount = new AtomicInteger();

        @Override
        public CompletableFuture<QrCode> requestQrCode() {
            return CompletableFuture.completedFuture(new QrCode(
                    "qr-token", "qr-content", Instant.now().plusSeconds(10)));
        }

        @Override
        public CompletableFuture<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
            pollCount.incrementAndGet();
            Object result = results.poll();
            if (result instanceof Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(
                    result == null ? LoginPollResult.waiting() : (LoginPollResult) result);
        }
    }
}
