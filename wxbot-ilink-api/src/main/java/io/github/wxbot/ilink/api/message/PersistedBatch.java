/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

import java.util.List;

/**
 * 消息批次和新游标原子持久化后的结果。
 *
 * @param cursor 已提交的新游标
 * @param acceptedMessages 本批首次进入收件箱的消息，重复消息不会再次返回
 */
public record PersistedBatch(String cursor, List<StoredMessage> acceptedMessages) {

    public PersistedBatch {
        cursor = cursor == null ? "" : cursor;
        acceptedMessages = List.copyOf(acceptedMessages);
    }
}
