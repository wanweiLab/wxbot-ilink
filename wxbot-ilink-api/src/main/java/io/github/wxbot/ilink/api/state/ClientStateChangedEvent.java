/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.state;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次已经完成的客户端状态变更。
 *
 * @param sequence 客户端内单调递增的事件序号，可用于识别回调顺序
 * @param previous 变更前状态
 * @param current 变更后状态
 * @param reason 触发变更的可读原因，不得包含凭证等敏感信息
 * @param occurredAt 状态变更时间
 */
public record ClientStateChangedEvent(
        long sequence,
        ClientState previous,
        ClientState current,
        String reason,
        Instant occurredAt) {

    /**
     * 校验状态事件，防止无效事件进入监听器和监控系统。
     */
    public ClientStateChangedEvent {
        if (sequence <= 0) {
            throw new IllegalArgumentException("事件序号必须大于零");
        }
        Objects.requireNonNull(previous, "变更前状态不能为空");
        Objects.requireNonNull(current, "变更后状态不能为空");
        Objects.requireNonNull(occurredAt, "状态变更时间不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("状态变更原因不能为空");
        }
    }
}
