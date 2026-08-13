/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 客户端可恢复状态的版本化快照。
 *
 * <p>快照包含访问令牌和上下文令牌，持久化实现必须加密保存，且不得记录原文日志。
 *
 * @param schemaVersion 快照结构版本
 * @param session Bot 会话
 * @param cursor 最近安全提交的消息游标
 * @param conversations 按用户标识保存的会话快照
 * @param savedAt 保存时间
 */
public record ClientSnapshot(
        int schemaVersion,
        BotSession session,
        String cursor,
        Map<String, ConversationSnapshot> conversations,
        Instant savedAt) {

    /** 当前能够读写的快照结构版本。 */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ClientSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("不支持的快照结构版本：" + schemaVersion);
        }
        Objects.requireNonNull(session, "Bot 会话不能为空");
        cursor = cursor == null ? "" : cursor;
        conversations = Map.copyOf(Objects.requireNonNull(conversations, "会话快照不能为空"));
        Objects.requireNonNull(savedAt, "保存时间不能为空");
    }

    @Override
    public String toString() {
        return "ClientSnapshot[schemaVersion=" + schemaVersion + ", session=" + session
                + ", cursor=***, conversationCount=" + conversations.size()
                + ", savedAt=" + savedAt + "]";
    }
}
