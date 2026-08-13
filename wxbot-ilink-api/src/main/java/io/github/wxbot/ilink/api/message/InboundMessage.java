/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 从 iLink 服务端接收的不可变消息。
 *
 * @param messageId 服务端消息标识，用于去重和业务幂等
 * @param fromUserId 发送用户标识
 * @param toUserId 接收方标识
 * @param createdAt 服务端消息创建时间
 * @param contextToken 回复该会话所需的上下文令牌
 * @param items 消息项列表
 */
public record InboundMessage(
        long messageId,
        String fromUserId,
        String toUserId,
        Instant createdAt,
        String contextToken,
        List<MessageItem> items) {

    public InboundMessage {
        if (messageId <= 0) {
            throw new IllegalArgumentException("消息标识必须大于零");
        }
        fromUserId = required(fromUserId, "发送用户标识");
        toUserId = required(toUserId, "接收方标识");
        Objects.requireNonNull(createdAt, "消息创建时间不能为空");
        contextToken = required(contextToken, "上下文令牌");
        items = List.copyOf(Objects.requireNonNull(items, "消息项列表不能为空"));
    }

    @Override
    public String toString() {
        return "InboundMessage[messageId=" + messageId + ", fromUserId="+ fromUserId +", toUserId=" +toUserId
                + ", createdAt=" + createdAt + ", contextToken="+contextToken+", item=" + items + "]";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
