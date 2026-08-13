/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.concurrent.CompletionStage;

/**
 * 一条等待业务确认的消息投递。
 *
 * <p>{@link #ack()} 和 {@link #retry(Throwable)} 均为幂等操作，只有第一次结束操作生效。
 */
public interface MessageDelivery {

    /** @return 当前投递的入站消息 */
    InboundMessage message();

    /** @return 当前投递次数，第一次为 1 */
    int attempt();

    /**
     * 返回本次投递最终完成阶段。
     *
     * <p>显式确认、重试或进入死信的存储操作结束后完成；供 Flow 订阅适配等待可靠处理结果。
     *
     * @return 投递最终结果
     */
    CompletionStage<Void> completion();

    /** @return 确认业务处理成功的异步结果 */
    CompletionStage<Void> ack();

    /**
     * 标记本次处理失败并等待重新投递。
     *
     * @param cause 已脱敏的失败原因
     * @return 状态保存完成阶段
     */
    CompletionStage<Void> retry(Throwable cause);
}
