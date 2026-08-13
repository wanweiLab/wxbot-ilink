/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.testkit;

import io.github.wxbot.ilink.api.message.InboundMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 可嵌入 CI 或预发布环境的本地长稳测试驱动器。
 *
 * <p>驱动器按固定速率生成确定性消息并调用业务提供的异步处理函数，统计成功、失败和最大在途量。它不访问真实
 * iLink 服务，因此只用于发现资源泄漏、无界并发和长时间处理退化，不能替代真实网络容量认证。
 */
public final class LocalSoakHarness {

    /** 长稳测试结果。 */
    public record Result(long submitted, long succeeded, long failed, long maxInFlight) {
    }

    /**
     * 在指定时间内执行固定速率负载。
     *
     * @param duration 运行时长
     * @param messagesPerSecond 每秒生成消息数
     * @param handler 异步消息处理函数
     * @return 所有已提交消息结束后的结果
     */
    public CompletionStage<Result> run(
            Duration duration,
            int messagesPerSecond,
            Function<InboundMessage, CompletionStage<Void>> handler) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("长稳测试时长必须大于零"));
        }
        if (messagesPerSecond <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("每秒消息数必须大于零"));
        }
        Objects.requireNonNull(handler, "消息处理函数不能为空");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wxbot-ilink-soak-harness");
            thread.setDaemon(true);
            return thread;
        });
        CompletableFuture<Result> result = new CompletableFuture<>();
        List<CompletableFuture<Void>> operations = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong submitted = new AtomicLong();
        AtomicLong succeeded = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong inFlight = new AtomicLong();
        AtomicLong maxInFlight = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        long periodNanos = Math.max(1L, TimeUnit.SECONDS.toNanos(1) / messagesPerSecond);
        Runnable submit = () -> {
            if (stopped.get()) {
                return;
            }
            long id = submitted.incrementAndGet();
            long active = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(active, Math::max);
            CompletionStage<Void> stage;
            try {
                stage = Objects.requireNonNull(handler.apply(message(id)), "消息处理函数不能返回空阶段");
            } catch (Throwable failure) {
                stage = CompletableFuture.failedFuture(failure);
            }
            CompletableFuture<Void> operation = stage.handle((ignored, failure) -> {
                if (failure == null) {
                    succeeded.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                }
                inFlight.decrementAndGet();
                return (Void) null;
            }).toCompletableFuture();
            operations.add(operation);
        };
        scheduler.scheduleAtFixedRate(submit, 0L, periodNanos, TimeUnit.NANOSECONDS);
        scheduler.schedule(() -> {
            stopped.set(true);
            scheduler.shutdown();
            CompletableFuture<?>[] pending;
            synchronized (operations) {
                pending = operations.toArray(CompletableFuture[]::new);
            }
            CompletableFuture.allOf(pending).whenComplete((ignored, failure) -> result.complete(
                    new Result(submitted.get(), succeeded.get(), failed.get(), maxInFlight.get())));
        }, duration.toNanos(), TimeUnit.NANOSECONDS);
        return result;
    }

    private static InboundMessage message(long id) {
        return new InboundMessage(id, "soak-user-" + (id % 64L), "soak-bot",
                Instant.now(), "soak-context-" + id, List.of());
    }
}
