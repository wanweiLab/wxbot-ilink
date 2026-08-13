/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import io.github.wxbot.ilink.api.exception.TransportException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncRetryExecutorTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void 关闭调度器() throws InterruptedException {
        scheduler.shutdownNow();
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    }

    @Test
    void 可重试异常应在预算内再次尝试() {
        AsyncRetryExecutor executor = new AsyncRetryExecutor(
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(1)), scheduler);
        AtomicInteger attempts = new AtomicInteger();

        String value = executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                return CompletableFuture.failedFuture(
                        new TransportException("ILINK-NET-001", "临时网络失败", true, null));
            }
            return CompletableFuture.completedFuture("ok");
        }).toCompletableFuture().join();

        assertEquals("ok", value);
        assertEquals(3, attempts.get());
    }

    @Test
    void 不可重试异常不应再次尝试() {
        AsyncRetryExecutor executor = new AsyncRetryExecutor(
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(1)), scheduler);
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(CompletionException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new TransportException("ILINK-NET-002", "请求参数错误", false, null));
        }).toCompletableFuture().join());

        assertEquals(1, attempts.get());
    }

    @Test
    void 服务端RetryAfter应覆盖随机退避() {
        RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(5));
        TransportException failure = new TransportException(
                "ILINK-HTTP-429", "请求过多", true, null,
                429, null, null, "/send", 1, Duration.ofSeconds(2));

        assertEquals(Duration.ofSeconds(2), policy.nextDelay(failure, 1));
    }

    @Test
    void 重试预算耗尽时应停止故障放大() {
        AsyncRetryExecutor executor = new AsyncRetryExecutor(
                new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(1)), scheduler,
                new RetryBudget(Duration.ofMinutes(1), 0.2D, 0, 1, Clock.systemUTC()), null);
        AtomicInteger attempts = new AtomicInteger();

        CompletionException failure = assertThrows(CompletionException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new TransportException("ILINK-NET-001", "临时网络失败", true, null));
        }).toCompletableFuture().join());

        assertEquals("ILINK-RESILIENCE-002",
                ((io.github.wxbot.ilink.api.exception.ILinkException) failure.getCause()).errorCode());
        assertEquals(2, attempts.get());
    }

    @Test
    void 取消结果应取消当前异步操作且不再重试() throws Exception {
        AsyncRetryExecutor executor = new AsyncRetryExecutor(
                new RetryPolicy(3, Duration.ofMillis(20), Duration.ofMillis(20)), scheduler);
        CompletableFuture<String> protocolCall = new CompletableFuture<>();
        AtomicInteger attempts = new AtomicInteger();

        CompletableFuture<String> result = executor.execute(() -> {
            attempts.incrementAndGet();
            return protocolCall;
        }).toCompletableFuture();

        assertTrue(result.cancel(true));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!protocolCall.isCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }

        assertTrue(protocolCall.isCancelled());
        Thread.sleep(50L);
        assertEquals(1, attempts.get());
    }
}
