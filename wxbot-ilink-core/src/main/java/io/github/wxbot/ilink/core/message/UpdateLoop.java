/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.core.lifecycle.ConnectionSupervisor;
import io.github.wxbot.ilink.core.state.ClientStateMachine;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

/**
 * 串行驱动单一 {@link UpdatePoller} 的持续消息循环。
 *
 * <p>下一次拉取只会在上一次异步操作结束后调度，因此不依赖锁来修复并发 cursor 竞争。失败时连接监督器推进
 * 状态，并使用有上限的指数延迟，成功后立即继续长轮询。
 */
public final class UpdateLoop implements AutoCloseable {

    private final UpdatePoller poller;
    private final ConnectionSupervisor supervisor;
    private final ClientStateMachine stateMachine;
    private final ScheduledExecutorService scheduler;
    private final Duration baseFailureDelay;
    private final Duration maxFailureDelay;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
    private final MetricsSink metrics;
    private final Clock clock;

    public UpdateLoop(
            UpdatePoller poller,
            ConnectionSupervisor supervisor,
            ClientStateMachine stateMachine,
            ScheduledExecutorService scheduler,
            Duration baseFailureDelay,
            Duration maxFailureDelay) {
        this(poller, supervisor, stateMachine, scheduler, baseFailureDelay, maxFailureDelay,
                MetricsSink.noop(), Clock.systemUTC());
    }

    /** 创建带运行指标的单一消息循环。 */
    public UpdateLoop(
            UpdatePoller poller,
            ConnectionSupervisor supervisor,
            ClientStateMachine stateMachine,
            ScheduledExecutorService scheduler,
            Duration baseFailureDelay,
            Duration maxFailureDelay,
            MetricsSink metrics,
            Clock clock) {
        this.poller = Objects.requireNonNull(poller, "消息拉取器不能为空");
        this.supervisor = Objects.requireNonNull(supervisor, "连接监督器不能为空");
        this.stateMachine = Objects.requireNonNull(stateMachine, "状态机不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不能为空");
        this.baseFailureDelay = positive(baseFailureDelay, "基础失败延迟");
        this.maxFailureDelay = positive(maxFailureDelay, "最大失败延迟");
        if (maxFailureDelay.compareTo(baseFailureDelay) < 0) {
            throw new IllegalArgumentException("最大失败延迟不能小于基础失败延迟");
        }
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 启动循环，重复调用不会创建第二个循环。 */
    public boolean start() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        schedule(0L);
        return true;
    }

    private void poll() {
        if (!running.get() || terminalState()) {
            running.set(false);
            return;
        }
        Instant started = clock.instant();
        AtomicBoolean backpressured = new AtomicBoolean();
        poller.dispatchPending(256)
                .thenCompose(ignored -> {
                    if (!poller.hasDispatchCapacity()) {
                        backpressured.set(true);
                        return CompletableFuture.completedFuture(0);
                    }
                    return poller.pollOnce();
                })
                .whenComplete((count, failure) -> {
                    if (!running.get()) {
                        return;
                    }
                    if (failure == null) {
                        if (backpressured.get()) {
                            metrics.increment("ilink.poll.paused", Map.of("reason", "backpressure"));
                            schedule(baseFailureDelay.toMillis());
                            return;
                        }
                        metrics.recordDuration("ilink.poll.duration",
                                Duration.between(started, clock.instant()), Map.of());
                        metrics.increment("ilink.messages.received",
                                Map.of("result", count == 0 ? "empty" : "non_empty"));
                        supervisor.recordSuccess();
                        schedule(0L);
                    } else {
                        metrics.increment("ilink.poll.failures", Map.of());
                        supervisor.recordFailure(unwrap(failure));
                        if (terminalState()) {
                            running.set(false);
                        } else {
                            schedule(failureDelay().toMillis());
                        }
                    }
                });
    }

    private Duration failureDelay() {
        int shift = Math.min(20, Math.max(0, supervisor.consecutiveFailures() - 1));
        long factor = 1L << shift;
        long candidate;
        try {
            candidate = Math.multiplyExact(baseFailureDelay.toMillis(), factor);
        } catch (ArithmeticException ignored) {
            candidate = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(maxFailureDelay.toMillis(), candidate));
    }

    private void schedule(long delayMillis) {
        if (!running.get()) {
            return;
        }
        try {
            ScheduledFuture<?> next = scheduler.schedule(
                    this::poll, delayMillis, TimeUnit.MILLISECONDS);
            scheduled.set(next);
            if (!running.get() && scheduled.compareAndSet(next, null)) {
                next.cancel(false);
            }
        } catch (RejectedExecutionException ignored) {
            // 客户端关闭与异步拉取完成可能交错，关闭中的拒绝不应逃逸到协议回调线程。
            running.set(false);
        }
    }

    private boolean terminalState() {
        ClientState state = stateMachine.current();
        return state == ClientState.EXPIRED
                || state == ClientState.CLOSING
                || state == ClientState.CLOSED;
    }

    @Override
    public void close() {
        running.set(false);
        ScheduledFuture<?> task = scheduled.getAndSet(null);
        if (task != null) {
            task.cancel(true);
        }
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }
}
