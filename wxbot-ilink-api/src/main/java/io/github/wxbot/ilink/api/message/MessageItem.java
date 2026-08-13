/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.Map;

/**
 * 入站消息中的一个协议消息项。
 *
 * <p>{@code attributes} 保留服务端返回的结构化字段，使未知消息类型也能无损传递给上层。后续稳定消息类型
 * 会在公共 API 中增加类型安全的适配对象，但原始字段仍会保留。
 *
 * @param type 协议消息项类型
 * @param attributes 消息项字段的不可变副本
 */
public record MessageItem(int type, Map<String, Object> attributes) {

    public MessageItem {
        if (type <= 0) {
            throw new IllegalArgumentException("消息项类型必须大于零");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
