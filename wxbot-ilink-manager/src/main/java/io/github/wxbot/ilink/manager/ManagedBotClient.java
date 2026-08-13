/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.ILinkClient;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个 Bot 的客户端与独占协议资源。
 *
 * <p>共享连接池和数据库不应放入 {@code ownedResource}，否则停止单个 Bot 会影响其他用户。
 */
public final class ManagedBotClient implements AutoCloseable {
    private final ILinkClient client;
    private final AutoCloseable ownedResource;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ManagedBotClient(ILinkClient client, AutoCloseable ownedResource) {
        this.client = Objects.requireNonNull(client, "Bot 客户端不能为空");
        this.ownedResource = Objects.requireNonNull(ownedResource, "Bot 独占资源不能为空");
    }

    /** @return 独立 SDK 客户端 */
    public ILinkClient client() {
        return client;
    }

    /** @return 是否已经关闭 */
    public boolean closed() {
        return closed.get();
    }

    /** 先关闭客户端任务，再取消该 Bot 自己的协议请求。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            client.close();
        } finally {
            try {
                ownedResource.close();
            } catch (Exception failure) {
                throw new BotOperationException("关闭 Bot 协议资源失败", failure);
            }
        }
    }
}
