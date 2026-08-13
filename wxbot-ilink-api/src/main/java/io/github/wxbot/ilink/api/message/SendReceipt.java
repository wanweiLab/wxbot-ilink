/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.time.Instant;

/**
 * 服务端接受发送请求后的回执。
 *
 * @param clientId 客户端幂等标识
 * @param serverMessageId 服务端消息标识，协议未返回时为空
 * @param acceptedAt 接受时间
 */
public record SendReceipt(String clientId, String serverMessageId, Instant acceptedAt) {

    public SendReceipt {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("客户端幂等标识不能为空");
        }
        if (acceptedAt == null) {
            throw new IllegalArgumentException("接受时间不能为空");
        }
    }
}
