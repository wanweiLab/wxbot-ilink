/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.lifecycle;

import io.github.wxbot.ilink.api.exception.ILinkException;
import io.github.wxbot.ilink.api.exception.SessionExpiredException;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.core.state.ClientStateMachine;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 根据消息拉取结果监督连接健康状态。
 *
 * <p>单次可重试网络失败不会立即宣告断线；达到降级阈值后进入 {@code DEGRADED}，达到重连阈值后进入
 * {@code RECONNECTING}。不可重试的会话异常由上层直接转换到 {@code EXPIRED}。
 */
public final class ConnectionSupervisor {

    private final ClientStateMachine stateMachine;
    private final int degradedThreshold;
    private final int reconnectThreshold;
    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final AtomicReference<Instant> lastReconnect = new AtomicReference<>();

    public ConnectionSupervisor(
            ClientStateMachine stateMachine,
            int degradedThreshold,
            int reconnectThreshold,
            Clock clock) {
        this.stateMachine = Objects.requireNonNull(stateMachine, "状态机不能为空");
        if (degradedThreshold <= 0 || reconnectThreshold < degradedThreshold) {
            throw new IllegalArgumentException("重连阈值不能小于降级阈值，且阈值必须大于零");
        }
        this.degradedThreshold = degradedThreshold;
        this.reconnectThreshold = reconnectThreshold;
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 记录一次成功请求并恢复连接状态。 */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        lastSuccess.set(clock.instant());
        ClientState current = stateMachine.current();
        if (current == ClientState.DEGRADED || current == ClientState.RECONNECTING) {
            stateMachine.transitionTo(ClientState.CONNECTED, "消息拉取已经恢复");
        }
    }

    /** 记录一次请求失败并按阈值推进连接状态。 */
    public void recordFailure(Throwable failure) {
        Objects.requireNonNull(failure, "失败原因不能为空");
        if (failure instanceof SessionExpiredException) {
            if (stateMachine.current() == ClientState.CONNECTED
                    || stateMachine.current() == ClientState.DEGRADED
                    || stateMachine.current() == ClientState.RECONNECTING) {
                stateMachine.transitionTo(ClientState.EXPIRED, "会话发生不可恢复错误");
            }
            return;
        }

        if (failure instanceof ILinkException exception && !exception.retryable()) {
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        ClientState current = stateMachine.current();
        if (failures >= reconnectThreshold
                && (current == ClientState.CONNECTED || current == ClientState.DEGRADED)) {
            lastReconnect.set(clock.instant());
            stateMachine.transitionTo(ClientState.RECONNECTING, "连续消息拉取失败，开始重连");
        } else if (failures >= degradedThreshold && current == ClientState.CONNECTED) {
            stateMachine.transitionTo(ClientState.DEGRADED, "连续消息拉取失败，连接已经降级");
        }
    }

    /** @return 连续失败次数 */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** @return 最近一次成功时间，尚未成功时为空 */
    public Instant lastSuccess() {
        return lastSuccess.get();
    }

    /** @return 最近进入重连状态的时间，尚未重连时为空 */
    public Instant lastReconnect() {
        return lastReconnect.get();
    }
}
