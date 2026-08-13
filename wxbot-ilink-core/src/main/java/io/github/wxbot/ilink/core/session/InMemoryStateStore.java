/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.StateStore;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 仅用于测试和单进程临时运行的内存状态存储。
 *
 * <p>该实现不提供进程重启恢复能力，也不会对敏感字段加密，不能作为默认生产存储。
 */
public final class InMemoryStateStore implements StateStore {

    private final ConcurrentMap<String, ClientSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Optional<ClientSnapshot>> load(String clientKey) {
        return CompletableFuture.completedFuture(Optional.ofNullable(snapshots.get(requiredKey(clientKey))));
    }

    @Override
    public CompletionStage<Void> save(String clientKey, ClientSnapshot snapshot) {
        if (snapshot == null) {
            return CompletableFuture.failedFuture(new NullPointerException("客户端快照不能为空"));
        }
        snapshots.put(requiredKey(clientKey), snapshot);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> clear(String clientKey) {
        snapshots.remove(requiredKey(clientKey));
        return CompletableFuture.completedFuture(null);
    }

    private static String requiredKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        return clientKey;
    }
}
