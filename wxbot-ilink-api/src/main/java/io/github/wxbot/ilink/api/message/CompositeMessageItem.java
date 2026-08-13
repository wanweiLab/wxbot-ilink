/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.Map;

/**
 * 组合出站消息中的一个协议项。
 *
 * <p>类型和字段名直接对应协议，以便新消息类型在 SDK 尚未发布类型安全模型时也能透传。字段名只允许字母、
 * 数字和下划线，避免构造歧义协议结构。
 *
 * @param type 协议消息项类型
 * @param field 协议载荷字段名
 * @param payload 该消息项的结构化载荷
 */
public record CompositeMessageItem(int type, String field, Map<String, Object> payload) {

    public CompositeMessageItem {
        if (type <= 0) {
            throw new IllegalArgumentException("组合消息项类型必须大于零");
        }
        if (field == null || !field.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("组合消息项字段名格式无效");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
