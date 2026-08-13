/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboxStore;
import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.PersistedBatch;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.message.DeadLetterMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 用于测试和单进程临时运行的内存收件箱。
 *
 * <p>所有写操作使用同一监视器模拟数据库事务，以验证 cursor 比较、去重和确认语义。该实现不提供进程重启恢复。
 */
public final class InMemoryInboxStore implements InboxStore {

    private final Map<String, ClientInbox> clients = new HashMap<>();
    private final Clock clock;

    public InMemoryInboxStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized CompletionStage<String> loadCursor(String clientKey) {
        return CompletableFuture.completedFuture(client(clientKey).cursor);
    }

    @Override
    public synchronized CompletionStage<PersistedBatch> persistBatch(
            String clientKey, String expectedCursor, UpdateBatch batch, Duration claimTimeout) {
        ClientInbox inbox = client(clientKey);
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("领取超时必须大于零"));
        }
        String expected = expectedCursor == null ? "" : expectedCursor;
        if (!inbox.cursor.equals(expected)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("消息游标已经被其他请求推进"));
        }

        List<StoredMessage> accepted = new ArrayList<>();
        for (InboundMessage message : batch.messages()) {
            if (!inbox.messages.containsKey(message.messageId())) {
                StoredMessage stored = new StoredMessage(clientKey, message, 1, clock.instant());
                Entry entry = new Entry(stored, false);
                entry.claimedUntil = clock.instant().plus(claimTimeout);
                inbox.messages.put(message.messageId(), entry);
                accepted.add(stored);
            }
        }
        inbox.cursor = batch.nextCursor();
        return CompletableFuture.completedFuture(new PersistedBatch(inbox.cursor, accepted));
    }

    @Override
    public synchronized CompletionStage<List<StoredMessage>> claimPending(
            String clientKey, Instant now, int limit, Duration claimTimeout) {
        if (limit <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("读取数量必须大于零"));
        }
        if (claimTimeout == null || claimTimeout.isZero() || claimTimeout.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("领取超时必须大于零"));
        }
        List<StoredMessage> pending = client(clientKey).messages.values().stream()
                .filter(entry -> !entry.acknowledged)
                .filter(entry -> entry.deadLetter == null)
                .filter(entry -> entry.claimedUntil == null || !entry.claimedUntil.isAfter(now))
                .map(entry -> entry.message)
                .filter(message -> !message.availableAt().isAfter(now))
                .sorted(Comparator.comparing((StoredMessage message) -> message.message().createdAt())
                        .thenComparingLong(message -> message.message().messageId()))
                .limit(limit)
                .toList();
        Instant claimedUntil = now.plus(claimTimeout);
        for (StoredMessage message : pending) {
            client(clientKey).messages.get(message.message().messageId()).claimedUntil = claimedUntil;
        }
        return CompletableFuture.completedFuture(pending);
    }

    @Override
    public synchronized CompletionStage<Void> acknowledge(String clientKey, long messageId) {
        Entry entry = client(clientKey).messages.get(messageId);
        if (entry != null) {
            entry.acknowledged = true;
            entry.claimedUntil = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> release(String clientKey, long messageId) {
        Entry entry = client(clientKey).messages.get(messageId);
        if (entry != null && !entry.acknowledged) {
            entry.claimedUntil = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> markForRetry(
            String clientKey, long messageId, Duration delay, String reason) {
        Entry entry = client(clientKey).messages.get(messageId);
        if (entry == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("消息不存在：" + messageId));
        }
        StoredMessage previous = entry.message;
        entry.message = new StoredMessage(
                clientKey, previous.message(), previous.attempt() + 1, clock.instant().plus(delay));
        entry.claimedUntil = null;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> deadLetter(
            String clientKey, long messageId, String reason, Instant failedAt) {
        Entry entry = client(clientKey).messages.get(messageId);
        if (entry == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("消息不存在：" + messageId));
        }
        if (!entry.acknowledged) {
            entry.deadLetter = new DeadLetterMessage(
                    entry.message.message(), entry.message.attempt(), sanitize(reason), failedAt);
            entry.claimedUntil = null;
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<List<DeadLetterMessage>> loadDeadLetters(
            String clientKey, int limit) {
        if (limit <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("读取数量必须大于零"));
        }
        return CompletableFuture.completedFuture(client(clientKey).messages.values().stream()
                .map(entry -> entry.deadLetter)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(DeadLetterMessage::failedAt)
                        .thenComparingLong(dead -> dead.message().messageId()))
                .limit(limit)
                .toList());
    }

    @Override
    public synchronized CompletionStage<Long> countPending(String clientKey) {
        long count = client(clientKey).messages.values().stream()
                .filter(entry -> !entry.acknowledged && entry.deadLetter == null)
                .count();
        return CompletableFuture.completedFuture(count);
    }

    private ClientInbox client(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        return clients.computeIfAbsent(clientKey, ignored -> new ClientInbox());
    }

    private static final class ClientInbox {
        private String cursor = "";
        private final Map<Long, Entry> messages = new HashMap<>();
    }

    private static final class Entry {
        private StoredMessage message;
        private boolean acknowledged;
        private Instant claimedUntil;
        private DeadLetterMessage deadLetter;

        private Entry(StoredMessage message, boolean acknowledged) {
            this.message = message;
            this.acknowledged = acknowledged;
        }
    }

    private static String sanitize(String reason) {
        if (reason == null || reason.isBlank()) {
            return "未知失败";
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
