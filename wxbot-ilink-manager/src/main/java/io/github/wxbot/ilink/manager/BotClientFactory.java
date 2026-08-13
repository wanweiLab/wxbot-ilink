/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.manager;

/** 为每个业务用户创建完全独立的 Bot 客户端和协议对象。 */
@FunctionalInterface
public interface BotClientFactory {
    /** 创建尚未登录或恢复的客户端。 */
    ManagedBotClient create(String userId, String clientKey);
}
