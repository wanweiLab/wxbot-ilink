/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.List;

/**
 * 一次消息长轮询响应。
 *
 * @param nextCursor 服务端返回的下一游标
 * @param messages 本批消息
 */
public record UpdateBatch(String nextCursor, List<InboundMessage> messages) {

    public UpdateBatch {
        nextCursor = nextCursor == null ? "" : nextCursor;
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
