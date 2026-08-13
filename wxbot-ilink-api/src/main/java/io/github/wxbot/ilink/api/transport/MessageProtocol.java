/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.transport;

import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.session.BotSession;

import java.util.concurrent.CompletionStage;

/** iLink 消息发送协议扩展点。 */
public interface MessageProtocol {

    /**
     * 发送一条已经解析上下文的消息。
     *
     * @param session 当前 Bot 会话
     * @param request 发送请求，其中上下文必须为显式令牌
     * @return 服务端回执
     */
    CompletionStage<SendReceipt> send(BotSession session, SendMessageRequest request);

    /** 获取输入态票据。 */
    CompletionStage<String> requestTypingTicket(
            BotSession session, String userId, String contextToken);

    /** 设置输入状态，{@code typing=true} 表示开始输入。 */
    CompletionStage<Void> setTyping(
            BotSession session, String userId, String ticket, boolean typing);
}
