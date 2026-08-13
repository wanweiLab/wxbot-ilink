/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

/** 消息处理阶段成功结束后的确认模式。 */
public enum AcknowledgementMode {
    /** 处理阶段成功时由 SDK 自动确认，失败时自动重试或进入死信。 */
    AUTO,

    /** 业务必须显式调用 {@link MessageDelivery#ack()} 或 {@link MessageDelivery#retry(Throwable)}。 */
    MANUAL
}
