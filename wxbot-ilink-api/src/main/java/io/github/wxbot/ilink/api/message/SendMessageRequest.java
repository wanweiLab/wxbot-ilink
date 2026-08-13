/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.Map;
import java.util.List;

/**
 * 通用出站消息请求。
 *
 * <p>媒体数据本身由媒体模块上传，本对象只携带协议消息字段。{@code clientId} 一旦生成，在自动重试期间必须
 * 保持不变，才能依赖服务端幂等语义。
 *
 * @param clientId 幂等客户端标识
 * @param toUserId 目标用户
 * @param type 消息类型
 * @param context 上下文引用
 * @param payload 消息字段
 */
public record SendMessageRequest(
        String clientId,
        String toUserId,
        OutboundMessageType type,
        ContextReference context,
        Map<String, Object> payload) {

    public SendMessageRequest {
        clientId = required(clientId, "客户端幂等标识");
        toUserId = required(toUserId, "目标用户标识");
        if (type == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }
        context = context == null ? ContextReference.latest() : context;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** 创建文本消息请求。 */
    public static SendMessageRequest text(String clientId, String toUserId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本内容不能为空");
        }
        return new SendMessageRequest(
                clientId, toUserId, OutboundMessageType.TEXT,
                ContextReference.latest(), Map.of("text", text));
    }

    /** 创建单次协议请求发送的组合消息。 */
    public static SendMessageRequest composite(
            String clientId, String toUserId, List<CompositeMessageItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("组合消息项不能为空");
        }
        return new SendMessageRequest(clientId, toUserId, OutboundMessageType.COMPOSITE,
                ContextReference.latest(), Map.of("items", List.copyOf(items)));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
