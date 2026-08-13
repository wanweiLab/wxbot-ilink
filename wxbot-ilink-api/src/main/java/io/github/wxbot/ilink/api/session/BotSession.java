/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.net.URI;
import java.util.Objects;

/**
 * 登录成功后建立的 Bot 会话。
 *
 * <p>对象不可变且线程安全。访问令牌和微信身份均属于敏感信息，{@link #toString()} 永远不会输出其原文。
 *
 * @param botToken 服务端签发的访问令牌
 * @param userId 登录用户标识
 * @param botId Bot 标识
 * @param baseUri 业务接口基础地址
 */
public record BotSession(String botToken, String userId, String botId, URI baseUri) {

    public BotSession {
        botToken = required(botToken, "访问令牌");
        userId = required(userId, "用户标识");
        botId = required(botId, "Bot 标识");
        Objects.requireNonNull(baseUri, "业务接口基础地址不能为空");
        if (!baseUri.isAbsolute()) {
            throw new IllegalArgumentException("业务接口基础地址必须是绝对地址");
        }
    }

    @Override
    public String toString() {
        return "BotSession[botToken=***, userId=" + mask(userId)
                + ", botId=" + mask(botId) + ", baseUri=" + baseUri + "]";
    }

    private static String required(String value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static String mask(String value) {
        if (value.length() <= 4) {
            return "***";
        }
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }
}
