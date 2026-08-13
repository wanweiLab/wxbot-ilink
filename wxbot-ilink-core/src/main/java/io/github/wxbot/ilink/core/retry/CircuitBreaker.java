/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 支持单探测半开状态的轻量熔断器。
 *
 * <p>连续失败达到阈值后进入打开状态；冷却时间结束后只允许一个请求探测。探测成功关闭并清零，失败则重新
 * 打开。熔断器只治理客户端主动发送请求，不改变可靠收件箱的投递语义。
 */
public final class CircuitBreaker {

    /** 熔断器公开状态。 */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;
    private State state = State.CLOSED;
    private int consecutiveFailures;
    private Instant openedAt;
    private boolean probeInFlight;

    public CircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("熔断失败阈值必须大于零");
        }
        if (openDuration == null || openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("熔断打开时间必须大于零");
        }
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** @return 当前请求是否获准执行 */
    public synchronized boolean tryAcquirePermission() {
        if (state == State.CLOSED) {
            return true;
        }
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        if (state == State.HALF_OPEN && !probeInFlight) {
            probeInFlight = true;
            return true;
        }
        return false;
    }

    /** 记录一次成功，半开探测成功会关闭熔断器。 */
    public synchronized void recordSuccess() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        openedAt = null;
        probeInFlight = false;
    }

    /** 记录一次失败，达到阈值或半开失败时打开熔断器。 */
    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.instant();
            probeInFlight = false;
        }
    }

    /** @return 当前状态；读取时会推进到半开状态但不会占用探测权 */
    public synchronized State state() {
        if (state == State.OPEN && !clock.instant().isBefore(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            probeInFlight = false;
        }
        return state;
    }
}
