/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.concurrent.CompletionStage;

/**
 * 异步消息处理器。
 *
 * <p>处理器由有界分发执行器调用。同一用户的回调严格串行，不同用户可以并行。返回阶段成功时由运行时自动
 * 确认消息，失败时安排重新投递。
 */
@FunctionalInterface
public interface MessageHandler {

    /**
     * 处理一条消息。
     *
     * @param delivery 消息投递
     * @return 业务处理完成阶段
     */
    CompletionStage<Void> onMessage(MessageDelivery delivery);
}
