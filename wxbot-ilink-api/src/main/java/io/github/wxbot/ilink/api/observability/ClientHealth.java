/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.observability;

import io.github.wxbot.ilink.api.state.ClientState;

import java.time.Instant;

/**
 * 客户端在某一时刻的只读健康快照。
 *
 * <p>时间为空表示对应事件尚未发生；积压数为 {@code -1} 表示当前存储实现不能低成本统计。快照不包含
 * userId、messageId、token 等高基数或敏感信息，可直接用于健康端点。
 *
 * @param state 当前生命周期状态
 * @param capturedAt 快照生成时间
 * @param lastSuccessfulPollAt 最近成功拉取时间
 * @param consecutivePollFailures 连续拉取失败次数
 * @param cursorUpdatedAt 最近一次持久化游标推进时间
 * @param inboxBacklog 持久化收件箱积压，未知时为 -1
 * @param dispatcherQueuedMessages 分发器排队消息数
 * @param lastReconnectAt 最近进入重连状态的时间
 */
public record ClientHealth(
        ClientState state,
        Instant capturedAt,
        Instant lastSuccessfulPollAt,
        int consecutivePollFailures,
        Instant cursorUpdatedAt,
        long inboxBacklog,
        int dispatcherQueuedMessages,
        Instant lastReconnectAt) {
}
