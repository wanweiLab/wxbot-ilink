/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个业务用户与一个 Bot 的永久绑定。
 *
 * <p>{@code userId} 是业务身份，{@code clientKey} 是其不可变散列隔离键。微信返回的 botId 属于加密会话，
 * 不参与绑定主键，因此重新建立 iLink 会话不会制造第二个业务 Bot。
 *
 * @param userId 业务用户唯一标识
 * @param clientKey SDK 存储隔离键
 * @param displayName 展示名称
 * @param status 生命周期状态
 * @param lastError 最近一次脱敏错误摘要
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param version 数据版本
 */
public record BotRegistration(
        String userId,
        String clientKey,
        String displayName,
        BotStatus status,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public BotRegistration {
        userId = required(userId, "用户唯一标识");
        clientKey = required(clientKey, "客户端隔离键");
        displayName = required(displayName, "展示名称");
        Objects.requireNonNull(status, "Bot 状态不能为空");
        Objects.requireNonNull(createdAt, "创建时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
        if (version < 0) {
            throw new IllegalArgumentException("数据版本不能小于零");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
