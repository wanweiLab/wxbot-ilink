/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.time.Instant;
import java.util.Objects;

/**
 * 已进入可靠收件箱、等待处理的消息。
 *
 * @param clientKey 客户端唯一键
 * @param message 入站消息
 * @param attempt 下一次投递次数
 * @param availableAt 最早可重新投递时间
 */
public record StoredMessage(
        String clientKey,
        InboundMessage message,
        int attempt,
        Instant availableAt) {

    public StoredMessage {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        Objects.requireNonNull(message, "入站消息不能为空");
        if (attempt <= 0) {
            throw new IllegalArgumentException("投递次数必须大于零");
        }
        Objects.requireNonNull(availableAt, "可投递时间不能为空");
    }
}
