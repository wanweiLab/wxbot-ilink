/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 客户端状态持久化扩展点。
 *
 * <p>所有方法均为异步契约，核心网络线程不会等待阻塞式存储。实现必须对同一个 {@code clientKey}
 * 保证写入顺序，并以原子方式替换完整快照。
 */
public interface StateStore {

    /**
     * 加载最近一次完整快照。
     *
     * @param clientKey 客户端唯一键
     * @return 快照查询结果
     */
    CompletionStage<Optional<ClientSnapshot>> load(String clientKey);

    /**
     * 原子保存完整快照。
     *
     * @param clientKey 客户端唯一键
     * @param snapshot 待保存快照
     * @return 保存完成阶段
     */
    CompletionStage<Void> save(String clientKey, ClientSnapshot snapshot);

    /**
     * 清除指定客户端的快照。
     *
     * @param clientKey 客户端唯一键
     * @return 清除完成阶段
     */
    CompletionStage<Void> clear(String clientKey);
}
