/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

/** SDK 支持的出站消息类型。 */
public enum OutboundMessageType {
    /** 文本消息。 */
    TEXT,
    /** 图片消息。 */
    IMAGE,
    /** 文件消息。 */
    FILE,
    /** 语音消息。 */
    VOICE,
    /** 视频消息。 */
    VIDEO,
    /** 单次协议请求携带多个消息项。 */
    COMPOSITE
}
