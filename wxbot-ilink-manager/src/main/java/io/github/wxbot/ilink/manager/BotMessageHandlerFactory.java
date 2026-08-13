/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.message.MessageHandler;

/** 为每个用户创建消息处理器，闭包中的 userId 可用于业务层精确路由。 */
@FunctionalInterface
public interface BotMessageHandlerFactory {
    /**
     * 创建一个 Bot 的消息处理器。
     *
     * @param userId 业务用户唯一标识
     * @param clientKey SDK 隔离键
     * @return 消息处理器
     */
    MessageHandler create(String userId, String clientKey);
}
