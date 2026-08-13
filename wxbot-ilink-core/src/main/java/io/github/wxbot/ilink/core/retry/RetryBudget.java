/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 每个客户端共享的滑动窗口重试预算。
 *
 * <p>每个首次请求产生一个基础额度，重试最多消费基础请求数量指定比例的额度；最小额度用于低流量 Bot，绝对
 * 上限避免流量突增时放大下游故障。该类同步方法临界区很小，可安全供多个发送线程共享。
 */
public final class RetryBudget {

    private final Duration window;
    private final double retryRatio;
    private final int minimumRetries;
    private final int maximumRetries;
    private final Clock clock;
    private final Deque<Instant> requests = new ArrayDeque<>();
    private final Deque<Instant> retries = new ArrayDeque<>();

    public RetryBudget(
            Duration window,
            double retryRatio,
            int minimumRetries,
            int maximumRetries,
            Clock clock) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("重试预算窗口必须大于零");
        }
        if (!(retryRatio > 0.0D && retryRatio <= 1.0D)) {
            throw new IllegalArgumentException("重试预算比例必须大于 0 且不超过 1");
        }
        if (minimumRetries < 0 || maximumRetries <= 0 || minimumRetries > maximumRetries) {
            throw new IllegalArgumentException("重试预算上下限无效");
        }
        this.window = window;
        this.retryRatio = retryRatio;
        this.minimumRetries = minimumRetries;
        this.maximumRetries = maximumRetries;
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 记录一次首次请求，为当前窗口补充可用重试额度。 */
    public synchronized void recordRequest() {
        Instant now = clock.instant();
        evict(now);
        requests.addLast(now);
    }

    /** @return 成功消费一次重试额度时返回 {@code true} */
    public synchronized boolean tryAcquireRetry() {
        Instant now = clock.instant();
        evict(now);
        int allowed = Math.min(maximumRetries,
                Math.max(minimumRetries, (int) Math.ceil(requests.size() * retryRatio)));
        if (retries.size() >= allowed) {
            return false;
        }
        retries.addLast(now);
        return true;
    }

    private void evict(Instant now) {
        Instant earliest = now.minus(window);
        while (!requests.isEmpty() && requests.peekFirst().isBefore(earliest)) {
            requests.removeFirst();
        }
        while (!retries.isEmpty() && retries.peekFirst().isBefore(earliest)) {
            retries.removeFirst();
        }
    }
}
