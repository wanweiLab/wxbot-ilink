/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次可跨后台副本查询的二维码登录尝试。
 *
 * <p>本记录只保存状态元数据，不保存二维码内容、令牌、微信用户标识或微信 Bot 标识。登录确认后的微信身份
 * 仍只存在于 SDK 的 AES-GCM 加密会话快照中，避免注册表形成第二份明文敏感数据。
 *
 * @param attemptId 登录尝试唯一标识
 * @param userId 业务用户唯一标识
 * @param phase 二维码登录阶段
 * @param message 可安全展示的阶段或错误消息
 * @param registrationStatus 此阶段对应的 Bot 注册状态
 * @param expiresAt 本次登录尝试失效时间；二维码生成前使用保护性期限
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param version 登录尝试数据版本
 */
public record BotLoginAttempt(
        String attemptId,
        String userId,
        BotLoginPhase phase,
        String message,
        BotStatus registrationStatus,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public BotLoginAttempt {
        attemptId = required(attemptId, "登录尝试标识");
        userId = required(userId, "业务用户标识");
        Objects.requireNonNull(phase, "登录阶段不能为空");
        Objects.requireNonNull(registrationStatus, "Bot 注册状态不能为空");
        Objects.requireNonNull(expiresAt, "登录尝试失效时间不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("登录尝试版本不能小于零");
        }
    }

    @Override
    public String toString() {
        return "BotLoginAttempt[attemptId=" + attemptId + ", userId=" + userId
                + ", phase=" + phase + ", message=" + message
                + ", registrationStatus=" + registrationStatus
                + ", expiresAt=" + expiresAt
                + ", createdAt=" + createdAt + ", updatedAt=" + updatedAt
                + ", version=" + version + "]";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
