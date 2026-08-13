/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import java.time.Instant;
import java.util.Objects;

/**
 * 管理后台可查询的二维码登录状态。
 *
 * <p>微信身份只会在绑定完成后从 AES-GCM 加密快照中读取，不会写入登录尝试表。访问本视图的接口必须经过
 * 管理员认证，并且永远不能加入会话令牌或二维码内容。
 *
 * @param attemptId 登录尝试唯一标识
 * @param phase 当前登录阶段
 * @param message 可安全展示的状态说明
 * @param registrationStatus Bot 注册状态
 * @param wechatUserId 微信 iLink 用户标识；绑定完成前为空
 * @param botId 微信 iLink Bot 标识；绑定完成前为空
 * @param expiresAt 二维码失效时间
 * @param createdAt 登录尝试创建时间
 * @param updatedAt 状态最近更新时间
 * @param version 登录尝试数据版本
 */
public record BotLoginStatusView(
        String attemptId,
        BotLoginPhase phase,
        String message,
        BotStatus registrationStatus,
        String wechatUserId,
        String botId,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public BotLoginStatusView {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("登录尝试标识不能为空");
        }
        Objects.requireNonNull(phase, "登录阶段不能为空");
        Objects.requireNonNull(registrationStatus, "Bot 注册状态不能为空");
        Objects.requireNonNull(expiresAt, "二维码失效时间不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if ((wechatUserId == null) != (botId == null)) {
            throw new IllegalArgumentException("微信 userId 和 botId 必须同时存在或同时为空");
        }
        if (phase == BotLoginPhase.BOUND && wechatUserId == null) {
            throw new IllegalArgumentException("绑定完成状态必须包含微信身份");
        }
    }

    @Override
    public String toString() {
        return "BotLoginStatusView[attemptId=" + attemptId + ", phase=" + phase
                + ", message=" + message + ", registrationStatus=" + registrationStatus
                + ", wechatUserId=***, botId=***, expiresAt=" + expiresAt
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", version=" + version + "]";
    }
}
