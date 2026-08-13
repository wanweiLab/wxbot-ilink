/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import io.github.wxbot.ilink.api.session.LeaseStore;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * 能在同一事务中校验租约并提交消息批次的收件箱。
 *
 * <p>仅在持久化前读取本地“仍持有租约”标记无法消除检查与提交之间的竞态。多实例客户端必须使用本接口，让
 * 租约所有权、消息去重与 cursor 推进共享一个原子边界。
 */
public interface FencedInboxStore extends InboxStore, LeaseStore {

    /**
     * 仅当指定 owner 的租约尚未过期时原子提交批次。
     *
     * @param clientKey 客户端唯一键
     * @param ownerId 当前运行实例唯一标识
     * @param now 本次租约校验时间
     * @param expectedCursor 发起请求时使用的游标
     * @param batch 服务端响应批次
     * @param claimTimeout 新消息的首次领取有效期
     * @return 持久化结果
     */
    CompletionStage<PersistedBatch> persistBatchWhileLeaseHeld(
            String clientKey,
            String ownerId,
            Instant now,
            String expectedCursor,
            UpdateBatch batch,
            Duration claimTimeout);
}
