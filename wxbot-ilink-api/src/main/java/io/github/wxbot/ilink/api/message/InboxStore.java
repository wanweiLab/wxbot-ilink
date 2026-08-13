/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * 可靠消息收件箱扩展点。
 *
 * <p>{@link #persistBatch} 必须在同一原子操作中完成消息去重、消息保存和 cursor 推进。进程在该操作之后退出时，
 * 未确认消息必须能通过 {@link #claimPending(String, Instant, int, Duration)} 恢复。
 */
public interface InboxStore {

    /** @param clientKey 客户端唯一键；@return 最近安全提交的游标 */
    CompletionStage<String> loadCursor(String clientKey);

    /**
     * 原子保存一批消息并推进游标。
     *
     * @param clientKey 客户端唯一键
     * @param expectedCursor 发起请求时使用的游标，用于阻止过期响应覆盖新游标
     * @param batch 服务端响应批次
     * @return 持久化结果
     */
    CompletionStage<PersistedBatch> persistBatch(
            String clientKey, String expectedCursor, UpdateBatch batch, Duration claimTimeout);

    /**
     * 读取可以立即投递的未确认消息。
     *
     * @param clientKey 客户端唯一键
     * @param now 当前时间
     * @param limit 最大返回数量
     * @return 待投递消息
     */
    CompletionStage<List<StoredMessage>> claimPending(
            String clientKey, Instant now, int limit, Duration claimTimeout);

    /** @return 确认结果保存完成阶段 */
    CompletionStage<Void> acknowledge(String clientKey, long messageId);

    /**
     * 分发器未能接收消息时立即释放领取权，使消息可以被下一轮恢复。
     *
     * @param clientKey 客户端唯一键
     * @param messageId 消息标识
     * @return 释放完成阶段
     */
    CompletionStage<Void> release(String clientKey, long messageId);

    /**
     * 保存失败并延迟下一次投递。
     *
     * @param clientKey 客户端唯一键
     * @param messageId 消息标识
     * @param delay 重试延迟
     * @param reason 已脱敏的失败摘要
     * @return 状态保存完成阶段
     */
    CompletionStage<Void> markForRetry(
            String clientKey, long messageId, Duration delay, String reason);

    /**
     * 将消息从待投递集合原子移入死信集合。
     *
     * @param clientKey 客户端唯一键
     * @param messageId 消息标识
     * @param reason 已脱敏的最终失败摘要
     * @param failedAt 进入死信的时间
     * @return 状态保存完成阶段
     */
    CompletionStage<Void> deadLetter(
            String clientKey, long messageId, String reason, Instant failedAt);

    /**
     * 分页读取死信，按进入死信时间和消息标识排序。
     *
     * @param clientKey 客户端唯一键
     * @param limit 最大返回数量
     * @return 死信列表
     */
    CompletionStage<List<DeadLetterMessage>> loadDeadLetters(String clientKey, int limit);

    /**
     * 统计尚未确认且未进入死信的消息数量。
     *
     * @param clientKey 客户端唯一键
     * @return 当前积压数量
     */
    CompletionStage<Long> countPending(String clientKey);
}
