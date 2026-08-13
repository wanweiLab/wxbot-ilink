/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.lifecycle;

import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.api.session.LeaseStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 维护单个客户端运行租约，并在失租时触发安全停机。
 *
 * <p>续约采用“上一次完成后再调度下一次”的方式，避免存储变慢时积累并发续约。任何续约异常或所有权丢失都按
 * 失租处理；调用方必须立即停止消息拉取，不能用本地宽限期掩盖存储层已经给出的所有权结论。
 */
public final class LeaseGuard implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(LeaseGuard.class);

    private final LeaseStore store;
    private final String clientKey;
    private final String ownerId;
    private final Duration ttl;
    private final Duration renewInterval;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Runnable leaseLostHandler;
    private final MetricsSink metrics;
    private final AtomicBoolean holding = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

    /**
     * 创建租约守卫。
     *
     * @param store 原子租约存储
     * @param clientKey 客户端唯一键
     * @param ownerId 当前进程实例唯一标识
     * @param ttl 租约有效期
     * @param renewInterval 续约间隔
     * @param scheduler 非阻塞调度器
     * @param clock 生成租约时间的时钟
     * @param leaseLostHandler 失租后的同步停机回调
     * @param metrics 非阻塞指标出口
     */
    public LeaseGuard(
            LeaseStore store,
            String clientKey,
            String ownerId,
            Duration ttl,
            Duration renewInterval,
            ScheduledExecutorService scheduler,
            Clock clock,
            Runnable leaseLostHandler,
            MetricsSink metrics) {
        this.store = Objects.requireNonNull(store, "租约存储不能为空");
        this.clientKey = required(clientKey, "客户端唯一键");
        this.ownerId = required(ownerId, "租约所有者标识");
        this.ttl = positive(ttl, "租约有效期");
        this.renewInterval = positive(renewInterval, "租约续约间隔");
        if (renewInterval.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("租约续约间隔必须小于租约有效期");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.leaseLostHandler = Objects.requireNonNull(leaseLostHandler, "失租处理器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
    }

    /**
     * 尝试取得租约，成功后自动启动续约。
     *
     * @return 是否取得租约
     */
    public CompletionStage<Boolean> acquire() {
        if (closed.get()) {
            return CompletableFuture.completedFuture(false);
        }
        return store.tryAcquire(clientKey, ownerId, clock.instant(), ttl)
                .thenCompose(acquired -> {
                    if (!acquired) {
                        LOGGER.info("客户端运行租约获取被拒绝，clientKey={}", safeKey(clientKey));
                        metrics.increment("ilink.lease.acquire", Map.of("result", "rejected"));
                        return CompletableFuture.completedFuture(false);
                    }
                    if (closed.get()) {
                        return store.release(clientKey, ownerId).thenApply(ignored -> false);
                    }
                    holding.set(true);
                    if (!scheduleRenewal(false)) {
                        // 关闭可能发生在取得后、发布续约任务前；只有仍持有标记的一方负责释放。
                        if (holding.compareAndSet(true, false)) {
                            return store.release(clientKey, ownerId)
                                    .handle((ignored, failure) -> false);
                        }
                        return CompletableFuture.completedFuture(false);
                    }
                    metrics.increment("ilink.lease.acquire", Map.of("result", "acquired"));
                    LOGGER.info("客户端运行租约守卫已启动，clientKey={}", safeKey(clientKey));
                    return CompletableFuture.completedFuture(true);
                });
    }

    /** @return 当前进程是否仍认为自己持有租约 */
    public boolean isHolding() {
        return holding.get() && !closed.get();
    }

    /**
     * 停止续约并异步释放当前租约。
     *
     * @return 释放操作完成阶段；未持有租约时立即完成
     */
    public CompletionStage<Void> closeAsync() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        ScheduledFuture<?> task = scheduled.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
        if (!holding.compareAndSet(true, false)) {
            return CompletableFuture.completedFuture(null);
        }
        return store.release(clientKey, ownerId).whenComplete((ignored, failure) -> {
            if (failure == null) {
                LOGGER.info("客户端运行租约守卫已关闭，clientKey={}", safeKey(clientKey));
            } else {
                LOGGER.warn("客户端运行租约释放失败，clientKey={}", safeKey(clientKey), failure);
            }
        });
    }

    /** 停止续约并发起租约释放，不阻塞调用线程。 */
    @Override
    public void close() {
        closeAsync();
    }

    private boolean scheduleRenewal(boolean notifyLoss) {
        if (closed.get() || !holding.get()) {
            return false;
        }
        ScheduledFuture<?> next;
        try {
            next = scheduler.schedule(
                    this::renew, renewInterval.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException failure) {
            // 无法安排续约时不能继续宣称持有租约，否则可能形成双活实例。
            if (notifyLoss && !closed.get()) {
                loseLease("scheduler_rejected");
            }
            return false;
        }
        ScheduledFuture<?> previous = scheduled.getAndSet(next);
        if (previous != null && previous != next && !previous.isDone()) {
            previous.cancel(false);
        }
        // 关闭可能恰好发生在 schedule 与引用发布之间，发布后再次校验并主动撤销任务。
        if (closed.get() || !holding.get()) {
            if (scheduled.compareAndSet(next, null)) {
                next.cancel(false);
            }
            return false;
        }
        return true;
    }

    private void renew() {
        if (closed.get() || !holding.get()) {
            return;
        }
        store.renew(clientKey, ownerId, clock.instant(), ttl)
                .whenComplete((renewed, failure) -> {
                    if (closed.get() || !holding.get()) {
                        return;
                    }
                    if (failure != null || !Boolean.TRUE.equals(renewed)) {
                        loseLease(failure == null ? "rejected" : "failure");
                    } else {
                        metrics.increment("ilink.lease.renew", Map.of("result", "success"));
                        scheduleRenewal(true);
                    }
                });
    }

    private void loseLease(String result) {
        if (!holding.compareAndSet(true, false)) {
            return;
        }
        metrics.increment("ilink.lease.renew", Map.of("result", result));
        LOGGER.error("客户端运行租约丢失，clientKey={}，result={}", safeKey(clientKey), result);
        leaseLostHandler.run();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    /** 日志只展示客户端隔离键尾部。 */
    private static String safeKey(String value) {
        return value.length() <= 8 ? "***" : "***" + value.substring(value.length() - 8);
    }
}
