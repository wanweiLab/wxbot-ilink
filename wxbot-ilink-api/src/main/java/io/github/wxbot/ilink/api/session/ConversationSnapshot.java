/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.time.Instant;
import java.util.Objects;

/**
 * 单个用户会话上下文的持久化快照。
 *
 * @param userId 用户标识
 * @param contextToken 最近一次有效上下文令牌
 * @param sourceMessageId 令牌来源消息标识
 * @param sourceMessageTime 令牌来源消息时间
 * @param updatedAt 快照更新时间
 */
public record ConversationSnapshot(
        String userId,
        String contextToken,
        long sourceMessageId,
        Instant sourceMessageTime,
        Instant updatedAt) {

    public ConversationSnapshot {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户标识不能为空");
        }
        if (contextToken == null || contextToken.isBlank()) {
            throw new IllegalArgumentException("上下文令牌不能为空");
        }
        Objects.requireNonNull(sourceMessageTime, "来源消息时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
    }

    @Override
    public String toString() {
        return "ConversationSnapshot[userId=***, contextToken=***, sourceMessageId="
                + sourceMessageId + ", sourceMessageTime=" + sourceMessageTime
                + ", updatedAt=" + updatedAt + "]";
    }
}
