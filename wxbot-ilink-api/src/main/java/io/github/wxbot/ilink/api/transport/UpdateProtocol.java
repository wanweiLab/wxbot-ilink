/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.transport;

import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;

import java.util.concurrent.CompletionStage;

/** iLink 消息长轮询协议扩展点。 */
public interface UpdateProtocol {

    /**
     * 发起一次消息长轮询。
     *
     * @param session 当前 Bot 会话
     * @param cursor 最近安全提交的游标
     * @return 消息批次
     */
    CompletionStage<UpdateBatch> poll(BotSession session, String cursor);
}
