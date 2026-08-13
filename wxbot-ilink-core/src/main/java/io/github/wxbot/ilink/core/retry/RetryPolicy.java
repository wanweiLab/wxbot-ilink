/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import io.github.wxbot.ilink.api.exception.ILinkException;
import io.github.wxbot.ilink.api.exception.TransportException;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 带完整随机抖动的指数退避策略。
 *
 * <p>{@code maxAttempts} 包含首次调用。只有明确标记为可重试的 SDK 异常才会重试，避免参数或认证错误形成风暴。
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("最大尝试次数必须大于零");
        }
        if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
            throw new IllegalArgumentException("基础退避时间必须大于零");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("最大退避时间不能小于基础退避时间");
        }
    }

    /** @return 当前失败是否允许继续尝试 */
    public boolean shouldRetry(Throwable failure, int completedAttempts) {
        return completedAttempts < maxAttempts
                && failure instanceof ILinkException exception
                && exception.retryable();
    }

    /**
     * 计算下一次尝试前的完整随机抖动。
     *
     * @param completedAttempts 已完成的尝试次数
     * @return 位于零和指数上限之间的延迟
     */
    public Duration nextDelay(int completedAttempts) {
        int shift = Math.min(30, Math.max(0, completedAttempts - 1));
        long exponential;
        try {
            exponential = Math.multiplyExact(baseDelay.toMillis(), 1L << shift);
        } catch (ArithmeticException ignored) {
            exponential = Long.MAX_VALUE;
        }
        long ceiling = Math.min(maxDelay.toMillis(), exponential);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }

    /**
     * 计算失败后的等待时间；服务端明确返回 Retry-After 时优先采用，并受最大退避上限保护。
     */
    public Duration nextDelay(Throwable failure, int completedAttempts) {
        if (failure instanceof TransportException transport && transport.retryAfter() != null) {
            return transport.retryAfter().compareTo(maxDelay) > 0 ? maxDelay : transport.retryAfter();
        }
        return nextDelay(completedAttempts);
    }
}
