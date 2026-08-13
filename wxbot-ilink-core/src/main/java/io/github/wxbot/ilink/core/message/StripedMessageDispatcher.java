/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboxStore;
import io.github.wxbot.ilink.api.message.AcknowledgementMode;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.MessageHandler;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.observability.MetricsSink;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按用户稳定分片的有界消息分发器。
 *
 * <p>同一用户始终映射到同一个单线程分片，因此天然保持顺序；不同分片可以并行。每个分片使用有界队列，队列
 * 满时立即拒绝，由 poller 停止继续拉取并在下一轮从 inbox 恢复，而不是无限占用内存。
 */
public final class StripedMessageDispatcher implements AutoCloseable {

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);

    private final ThreadPoolExecutor[] stripes;
    private final InboxStore inboxStore;
    private final MessageHandler handler;
    private final Duration processingTimeout;
    private final int maxDeliveryAttempts;
    private final AcknowledgementMode acknowledgementMode;
    private final Clock clock;
    private final MetricsSink metrics;
    private final Runnable inboxChanged;
    private final AtomicBoolean closed = new AtomicBoolean();

    public StripedMessageDispatcher(
            int stripeCount,
            int totalQueueCapacity,
            InboxStore inboxStore,
            MessageHandler handler) {
        this(stripeCount, totalQueueCapacity, inboxStore, handler,
                Duration.ofSeconds(30), 8, AcknowledgementMode.AUTO, Clock.systemUTC(),
                MetricsSink.noop(), () -> { });
    }

    /** 创建带处理超时、最大投递次数和确认模式的分发器。 */
    public StripedMessageDispatcher(
            int stripeCount,
            int totalQueueCapacity,
            InboxStore inboxStore,
            MessageHandler handler,
            Duration processingTimeout,
            int maxDeliveryAttempts,
            AcknowledgementMode acknowledgementMode) {
        this(stripeCount, totalQueueCapacity, inboxStore, handler, processingTimeout,
                maxDeliveryAttempts, acknowledgementMode, Clock.systemUTC(),
                MetricsSink.noop(), () -> { });
    }

    /** 创建时钟可注入的完整分发器。 */
    public StripedMessageDispatcher(
            int stripeCount,
            int totalQueueCapacity,
            InboxStore inboxStore,
            MessageHandler handler,
            Duration processingTimeout,
            int maxDeliveryAttempts,
            AcknowledgementMode acknowledgementMode,
            Clock clock) {
        this(stripeCount, totalQueueCapacity, inboxStore, handler, processingTimeout,
                maxDeliveryAttempts, acknowledgementMode, clock, MetricsSink.noop(), () -> { });
    }

    /** 创建带可靠消费指标和积压刷新通知的完整分发器。 */
    public StripedMessageDispatcher(
            int stripeCount,
            int totalQueueCapacity,
            InboxStore inboxStore,
            MessageHandler handler,
            Duration processingTimeout,
            int maxDeliveryAttempts,
            AcknowledgementMode acknowledgementMode,
            Clock clock,
            MetricsSink metrics,
            Runnable inboxChanged) {
        if (stripeCount <= 0 || totalQueueCapacity < stripeCount) {
            throw new IllegalArgumentException("分片数必须大于零，且总队列容量不能小于分片数");
        }
        this.inboxStore = Objects.requireNonNull(inboxStore, "收件箱不能为空");
        this.handler = Objects.requireNonNull(handler, "消息处理器不能为空");
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("消息处理超时必须大于零");
        }
        if (maxDeliveryAttempts <= 0) {
            throw new IllegalArgumentException("最大投递次数必须大于零");
        }
        this.processingTimeout = processingTimeout;
        this.maxDeliveryAttempts = maxDeliveryAttempts;
        this.acknowledgementMode = Objects.requireNonNull(acknowledgementMode, "确认模式不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
        this.inboxChanged = Objects.requireNonNull(inboxChanged, "积压刷新通知不能为空");
        this.stripes = new ThreadPoolExecutor[stripeCount];
        int perStripeCapacity = Math.max(1, totalQueueCapacity / stripeCount);
        for (int index = 0; index < stripeCount; index++) {
            int stripeIndex = index;
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "wxbot-ilink-dispatch-" + stripeIndex);
                thread.setDaemon(true);
                return thread;
            };
            stripes[index] = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(perStripeCapacity), factory,
                    new ThreadPoolExecutor.AbortPolicy());
        }
    }

    /**
     * 尝试提交一条持久化消息。
     *
     * @return 成功进入有界执行器时返回 {@code true}，队列已满或分发器关闭时返回 {@code false}
     */
    public boolean dispatch(StoredMessage stored) {
        Objects.requireNonNull(stored, "持久化消息不能为空");
        if (closed.get()) {
            return false;
        }
        int stripe = Math.floorMod(stored.message().fromUserId().hashCode(), stripes.length);
        try {
            stripes[stripe].execute(() -> handle(stored));
            recordQueueSize();
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    /** @return 当前所有分片中正在排队的任务数 */
    public int queuedTasks() {
        int total = 0;
        for (ThreadPoolExecutor stripe : stripes) {
            total += stripe.getQueue().size();
        }
        return total;
    }

    /**
     * @return 所有分片队列都仍有空位时返回 {@code true}；任一分片饱和时应暂停上游拉取
     */
    public boolean hasCapacity() {
        if (closed.get()) {
            return false;
        }
        for (ThreadPoolExecutor stripe : stripes) {
            if (stripe.getQueue().remainingCapacity() == 0) {
                return false;
            }
        }
        return true;
    }

    private void handle(StoredMessage stored) {
        metrics.recordDuration("ilink.messages.lag",
                nonNegativeDuration(stored.message().createdAt(), clock.instant()), Map.of());
        if (stored.attempt() > maxDeliveryAttempts) {
            inboxStore.deadLetter(
                    stored.clientKey(), stored.message().messageId(),
                    "消息投递次数超过上限", clock.instant())
                    .toCompletableFuture().join();
            metrics.increment("ilink.messages.dead_letter", Map.of());
            inboxChanged.run();
            recordQueueSize();
            return;
        }
        Delivery delivery = new Delivery(stored);
        CompletionStage<Void> result;
        try {
            result = handler.onMessage(delivery);
            if (result == null) {
                result = CompletableFuture.failedFuture(
                        new IllegalStateException("消息处理器不能返回空阶段"));
            }
        } catch (Throwable failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<Void> handlerFuture = result.toCompletableFuture();
        boolean handlerOwnsDeliveryCompletion = handlerFuture == delivery.completion().toCompletableFuture();
        // 超时施加在派生观察阶段，避免 orTimeout 先把原业务 Future 标记为异常完成，导致无法再取消。
        handlerFuture.thenApply(ignored -> (Void) null)
                .orTimeout(processingTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((ignored, failure) -> {
            if (delivery.finished.get()) {
                return CompletableFuture.<Void>completedFuture(null);
            }
            if (failure == null) {
                return acknowledgementMode == AcknowledgementMode.AUTO
                        ? delivery.ack() : CompletableFuture.<Void>completedFuture(null);
            }
            Throwable cause = unwrap(failure);
            if (cause instanceof TimeoutException && !handlerOwnsDeliveryCompletion) {
                handlerFuture.cancel(true);
            }
            return stored.attempt() >= maxDeliveryAttempts
                    ? delivery.deadLetter(cause) : delivery.retry(cause);
        }).thenCompose(stage -> stage)
                .whenComplete((ignored, failure) -> recordQueueSize())
                .toCompletableFuture().join();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ThreadPoolExecutor stripe : stripes) {
            stripe.shutdown();
        }
    }

    /** 在指定时间内等待分发任务结束，超时后取消剩余任务。 */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean terminated = true;
        for (ThreadPoolExecutor stripe : stripes) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !stripe.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                terminated = false;
                stripe.shutdownNow();
            }
        }
        return terminated;
    }

    private final class Delivery implements MessageDelivery {
        private final StoredMessage stored;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        private Delivery(StoredMessage stored) {
            this.stored = stored;
        }

        @Override
        public io.github.wxbot.ilink.api.message.InboundMessage message() {
            return stored.message();
        }

        @Override
        public int attempt() {
            return stored.attempt();
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        @Override
        public CompletionStage<Void> ack() {
            if (!finished.compareAndSet(false, true)) {
                return completion;
            }
            return finish(inboxStore.acknowledge(stored.clientKey(), stored.message().messageId()),
                    "ilink.messages.processed");
        }

        @Override
        public CompletionStage<Void> retry(Throwable cause) {
            if (!finished.compareAndSet(false, true)) {
                return completion;
            }
            String reason = cause == null ? "未知失败" : cause.getClass().getSimpleName();
            return finish(inboxStore.markForRetry(
                    stored.clientKey(), stored.message().messageId(), DEFAULT_RETRY_DELAY, reason),
                    "ilink.retry.count");
        }

        private CompletionStage<Void> deadLetter(Throwable cause) {
            if (!finished.compareAndSet(false, true)) {
                return completion;
            }
            String reason = cause == null ? "未知失败" : cause.getClass().getSimpleName();
            return finish(inboxStore.deadLetter(
                    stored.clientKey(), stored.message().messageId(), reason, clock.instant()),
                    "ilink.messages.dead_letter");
        }

        private CompletionStage<Void> finish(CompletionStage<Void> operation, String metricName) {
            operation.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    metrics.increment(metricName, Map.of());
                    inboxChanged.run();
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(unwrap(failure));
                }
            });
            return completion;
        }
    }

    private void recordQueueSize() {
        metrics.gauge("ilink.dispatch.queue.size", queuedTasks(), Map.of());
    }

    private static Duration nonNegativeDuration(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
