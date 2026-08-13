/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.time.Instant;
import java.util.Objects;

/**
 * 超过最大投递次数后停止自动重试的消息。
 *
 * @param message 原始入站消息
 * @param attempts 已执行的投递次数
 * @param reason 已脱敏的最后失败摘要
 * @param failedAt 进入死信的时间
 */
public record DeadLetterMessage(
        InboundMessage message,
        int attempts,
        String reason,
        Instant failedAt) {

    public DeadLetterMessage {
        Objects.requireNonNull(message, "入站消息不能为空");
        if (attempts <= 0) {
            throw new IllegalArgumentException("投递次数必须大于零");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("失败摘要不能为空");
        }
        Objects.requireNonNull(failedAt, "死信时间不能为空");
    }
}
