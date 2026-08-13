/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.context;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按用户管理最新上下文令牌。
 *
 * <p>更新使用消息时间和消息标识组成的顺序键，历史重放或乱序响应不能覆盖更新的上下文。
 */
public final class ConversationContextManager {

    private final ConcurrentMap<String, ConversationSnapshot> contexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, ConversationSnapshot> messageContexts = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    public ConversationContextManager(Clock clock) {
        this(clock, Duration.ofHours(24));
    }

    /** 创建带上下文有效期的管理器。 */
    public ConversationContextManager(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("上下文有效期必须大于零");
        }
        this.ttl = ttl;
    }

    /**
     * 尝试使用入站消息更新上下文。
     *
     * @param message 入站消息
     * @return 实际替换上下文时返回 {@code true}
     */
    public boolean update(InboundMessage message) {
        Objects.requireNonNull(message, "入站消息不能为空");
        ConversationSnapshot incoming = new ConversationSnapshot(
                message.fromUserId(), message.contextToken(), message.messageId(),
                message.createdAt(), clock.instant());
        boolean[] changed = new boolean[1];
        contexts.compute(message.fromUserId(), (userId, current) -> {
            if (current == null || newer(incoming, current)) {
                changed[0] = true;
                return incoming;
            }
            return current;
        });
        messageContexts.put(message.messageId(), incoming);
        return changed[0];
    }

    /** @return 指定用户最新上下文 */
    public Optional<ConversationSnapshot> find(String userId) {
        ConversationSnapshot snapshot = contexts.get(userId);
        if (snapshot != null && !clock.instant().isBefore(snapshot.updatedAt().plus(ttl))) {
            contexts.remove(userId, snapshot);
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot);
    }

    /** @return 指定来源消息携带的上下文；过期后为空 */
    public Optional<ConversationSnapshot> findByMessageId(long messageId) {
        ConversationSnapshot snapshot = messageContexts.get(messageId);
        if (snapshot != null && expired(snapshot)) {
            messageContexts.remove(messageId, snapshot);
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot);
    }

    /**
     * 显式使指定用户的上下文失效。
     *
     * @param userId 用户标识
     * @return 实际删除上下文时返回 {@code true}
     */
    public boolean invalidate(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("用户标识不能为空");
        }
        boolean removed = contexts.remove(userId) != null;
        messageContexts.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
        return removed;
    }

    /** @return 清理并返回已经超过 TTL 的上下文数量 */
    public int evictExpired() {
        int removed = 0;
        for (Map.Entry<String, ConversationSnapshot> entry : contexts.entrySet()) {
            if (expired(entry.getValue())
                    && contexts.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        for (Map.Entry<Long, ConversationSnapshot> entry : messageContexts.entrySet()) {
            if (expired(entry.getValue())) {
                messageContexts.remove(entry.getKey(), entry.getValue());
            }
        }
        return removed;
    }

    /** @return 当前上下文不可变快照 */
    public Map<String, ConversationSnapshot> snapshot() {
        evictExpired();
        return Map.copyOf(contexts);
    }

    /** @return 清理过期项后的当前上下文数量 */
    public int size() {
        evictExpired();
        return contexts.size();
    }

    /** 使用恢复快照替换当前上下文。 */
    public void restore(Map<String, ConversationSnapshot> snapshots) {
        contexts.clear();
        messageContexts.clear();
        if (snapshots != null) {
            contexts.putAll(snapshots);
            for (ConversationSnapshot snapshot : snapshots.values()) {
                messageContexts.put(snapshot.sourceMessageId(), snapshot);
            }
        }
    }

    private boolean expired(ConversationSnapshot snapshot) {
        return !clock.instant().isBefore(snapshot.updatedAt().plus(ttl));
    }

    private static boolean newer(ConversationSnapshot incoming, ConversationSnapshot current) {
        int timeOrder = incoming.sourceMessageTime().compareTo(current.sourceMessageTime());
        return timeOrder > 0
                || (timeOrder == 0 && incoming.sourceMessageId() > current.sourceMessageId());
    }
}
