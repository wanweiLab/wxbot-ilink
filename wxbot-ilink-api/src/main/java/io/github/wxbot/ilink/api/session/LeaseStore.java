/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * 多实例运行时的客户端租约存储。
 *
 * <p>同一 {@code clientKey} 在任一时刻只能由一个 owner 持有。调用方必须在租约过期前续约；续约失败后应
 * 立即停止 poller，避免两个实例同时推进 cursor。
 */
public interface LeaseStore {

    /** 尝试取得或续订已经属于当前 owner 的租约。 */
    CompletionStage<Boolean> tryAcquire(
            String clientKey, String ownerId, Instant now, Duration ttl);

    /** 仅当租约仍属于当前 owner 且尚未过期时续约。 */
    CompletionStage<Boolean> renew(
            String clientKey, String ownerId, Instant now, Duration ttl);

    /** 释放当前 owner 持有的租约；重复释放是安全的。 */
    CompletionStage<Void> release(String clientKey, String ownerId);
}
