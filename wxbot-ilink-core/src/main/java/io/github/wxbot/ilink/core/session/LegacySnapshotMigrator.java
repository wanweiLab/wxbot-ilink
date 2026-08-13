/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;
import io.github.wxbot.ilink.api.session.LegacyResumeData;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 将旧 SDK 的恢复字段转换为版本化客户端快照。 */
public final class LegacySnapshotMigrator {
    private LegacySnapshotMigrator() {
    }

    /**
     * 转换旧恢复数据。
     *
     * <p>旧格式没有上下文来源消息，因此使用消息号 {@code 0} 和导出时间作为降级排序依据；收到第一条新消息
     * 后就会被精确信息替换。
     */
    public static ClientSnapshot migrate(LegacyResumeData legacy, Clock clock) {
        Objects.requireNonNull(legacy, "旧恢复数据不能为空");
        Objects.requireNonNull(clock, "时钟不能为空");
        Instant migratedAt = legacy.exportedAt() == null ? clock.instant() : legacy.exportedAt();
        Map<String, ConversationSnapshot> conversations = new LinkedHashMap<>();
        if (legacy.contextTokens() != null) {
            legacy.contextTokens().forEach((userId, token) -> conversations.put(userId,
                    new ConversationSnapshot(userId, token, 0L, migratedAt, migratedAt)));
        }
        return new ClientSnapshot(ClientSnapshot.CURRENT_SCHEMA_VERSION,
                new BotSession(legacy.botToken(), legacy.userId(), legacy.botId(), legacy.baseUri()),
                legacy.cursor(), conversations, clock.instant());
    }
}
