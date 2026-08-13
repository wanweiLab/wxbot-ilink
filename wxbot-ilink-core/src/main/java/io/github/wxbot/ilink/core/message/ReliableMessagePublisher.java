/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.MessageHandler;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 只允许一个可靠消费者的 JDK Flow 发布器。
 *
 * <p>发布器不建立第二个内存队列：订阅者没有需求量时立即拒绝本次投递，消息由持久化 inbox 延后重试。这样
 * Flow 背压与 SDK 的可靠消息语义共用一个有界缓冲层，不会出现已经超时的投递仍滞留在发布器队列中。
 */
public final class ReliableMessagePublisher
        implements Flow.Publisher<MessageDelivery>, MessageHandler, AutoCloseable {

    private final AtomicReference<Subscription> subscription = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 注册唯一消息消费者，第二个订阅者会收到错误。 */
    @Override
    public void subscribe(Flow.Subscriber<? super MessageDelivery> subscriber) {
        Objects.requireNonNull(subscriber, "订阅者不能为空");
        Subscription created = new Subscription(subscriber);
        if (closed.get() || !subscription.compareAndSet(null, created)) {
            subscriber.onSubscribe(EmptySubscription.INSTANCE);
            subscriber.onError(new IllegalStateException("可靠消息发布器只允许一个消费订阅者"));
            return;
        }
        subscriber.onSubscribe(created);
    }

    /**
     * 将持久化投递交给已有需求量的订阅者。
     *
     * @return 订阅者完成 ack 或 retry 后结束的阶段
     */
    @Override
    public CompletionStage<Void> onMessage(MessageDelivery delivery) {
        Objects.requireNonNull(delivery, "消息投递不能为空");
        Subscription current = subscription.get();
        if (closed.get() || current == null || current.cancelled.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("没有可用的消息订阅者"));
        }
        if (!current.tryConsumeDemand()) {
            return CompletableFuture.failedFuture(new IllegalStateException("消息订阅者没有可用需求量"));
        }
        try {
            current.subscriber.onNext(delivery);
            return delivery.completion();
        } catch (Throwable failure) {
            current.cancel();
            try {
                current.subscriber.onError(failure);
            } catch (Throwable ignored) {
                // 订阅者错误回调是最终隔离边界，不能覆盖原始业务异常。
            }
            return delivery.retry(failure);
        }
    }

    /** 关闭发布器并通知当前订阅者。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Subscription current = subscription.getAndSet(null);
        if (current != null && !current.cancelled.getAndSet(true)) {
            try {
                current.subscriber.onComplete();
            } catch (Throwable ignored) {
                // 用户完成回调异常不得阻断客户端关闭。
            }
        }
    }

    private final class Subscription implements Flow.Subscription {
        private final Flow.Subscriber<? super MessageDelivery> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Subscription(Flow.Subscriber<? super MessageDelivery> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                cancel();
                subscriber.onError(new IllegalArgumentException("订阅需求量必须大于零"));
                return;
            }
            demand.getAndUpdate(current -> addCap(current, n));
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                subscription.compareAndSet(this, null);
            }
        }

        private boolean tryConsumeDemand() {
            while (!cancelled.get()) {
                long current = demand.get();
                if (current == 0L) {
                    return false;
                }
                long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current - 1L;
                if (demand.compareAndSet(current, next)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static long addCap(long left, long right) {
        long total = left + right;
        return total < 0L ? Long.MAX_VALUE : total;
    }

    private enum EmptySubscription implements Flow.Subscription {
        INSTANCE;

        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }
}
