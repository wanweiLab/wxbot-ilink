/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.exception.ILinkException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.message.InboxStore;
import io.github.wxbot.ilink.api.message.FencedInboxStore;
import io.github.wxbot.ilink.api.message.PersistedBatch;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.UpdateProtocol;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/**
 * 每个客户端唯一的消息拉取器。
 *
 * <p>一次 {@link #pollOnce()} 完成前拒绝下一次调用，从结构上保证同一客户端最多一个请求在途。消息和 cursor
 * 原子持久化成功后才更新上下文并提交分发，进程崩溃时可以从 inbox 恢复未确认消息。
 */
public final class UpdatePoller {

    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdatePoller.class);

    private final String clientKey;
    private final BotSession session;
    private final UpdateProtocol protocol;
    private final InboxStore inboxStore;
    private final ConversationContextManager contextManager;
    private final StripedMessageDispatcher dispatcher;
    private final Clock clock;
    private final String leaseOwnerId;
    private final MetricsSink metrics;
    private final LongConsumer backlogUpdater;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<?>> activeProtocolCall = new AtomicReference<>();
    private final AtomicReference<Instant> cursorUpdatedAt = new AtomicReference<>();

    public UpdatePoller(
            String clientKey,
            BotSession session,
            UpdateProtocol protocol,
            InboxStore inboxStore,
            ConversationContextManager contextManager,
            StripedMessageDispatcher dispatcher,
            Clock clock) {
        this(clientKey, session, protocol, inboxStore, contextManager, dispatcher, clock, null);
    }

    /** 创建可选受数据库租约保护的消息拉取器。 */
    public UpdatePoller(
            String clientKey,
            BotSession session,
            UpdateProtocol protocol,
            InboxStore inboxStore,
            ConversationContextManager contextManager,
            StripedMessageDispatcher dispatcher,
            Clock clock,
            String leaseOwnerId) {
        this(clientKey, session, protocol, inboxStore, contextManager, dispatcher, clock,
                leaseOwnerId, MetricsSink.noop(), ignored -> { });
    }

    /** 创建带完整消息指标和异步积压缓存更新器的拉取器。 */
    public UpdatePoller(
            String clientKey,
            BotSession session,
            UpdateProtocol protocol,
            InboxStore inboxStore,
            ConversationContextManager contextManager,
            StripedMessageDispatcher dispatcher,
            Clock clock,
            String leaseOwnerId,
            MetricsSink metrics,
            LongConsumer backlogUpdater) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        this.clientKey = clientKey;
        this.session = Objects.requireNonNull(session, "Bot 会话不能为空");
        this.protocol = Objects.requireNonNull(protocol, "消息协议不能为空");
        this.inboxStore = Objects.requireNonNull(inboxStore, "收件箱不能为空");
        this.contextManager = Objects.requireNonNull(contextManager, "上下文管理器不能为空");
        this.dispatcher = Objects.requireNonNull(dispatcher, "消息分发器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        if (leaseOwnerId != null && !(inboxStore instanceof FencedInboxStore)) {
            throw new IllegalArgumentException("多实例模式需要支持租约围栏的收件箱存储");
        }
        this.leaseOwnerId = leaseOwnerId;
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
        this.backlogUpdater = Objects.requireNonNull(backlogUpdater, "积压更新器不能为空");
    }

    /**
     * 执行一次完整的可靠消息拉取。
     *
     * @return 本次首次写入 inbox 的消息数量
     */
    public CompletionStage<Integer> pollOnce() {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ClientClosedException("消息拉取器"));
        }
        if (!polling.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("已有消息拉取请求正在执行"));
        }

        CompletionStage<Integer> operation = inboxStore.loadCursor(clientKey)
                .thenCompose(cursor -> pollProtocol(cursor)
                        .thenCompose(batch -> persistBatch(cursor, batch)))
                .thenApply(batch -> {
                    cursorUpdatedAt.set(clock.instant());
                    return dispatchPersisted(batch);
                });
        return operation.whenComplete((result, failure) -> {
            polling.set(false);
            if (failure != null && !closed.get()) {
                logPollFailure(unwrap(failure));
            } else if (result != null && result > 0) {
                LOGGER.debug("消息拉取并提交成功，clientKey={}，新增消息数={}",
                        safeKey(clientKey), result);
            }
            refreshBacklog();
        });
    }

    private CompletionStage<io.github.wxbot.ilink.api.message.UpdateBatch> pollProtocol(
            String cursor) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ClientClosedException("消息拉取器"));
        }
        CompletableFuture<io.github.wxbot.ilink.api.message.UpdateBatch> call;
        try {
            call = Objects.requireNonNull(protocol.poll(session, cursor),
                    "消息拉取协议不能返回空阶段").toCompletableFuture();
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        activeProtocolCall.set(call);
        if (closed.get()) {
            activeProtocolCall.compareAndSet(call, null);
            call.cancel(true);
        }
        return call.whenComplete((ignored, failure) ->
                activeProtocolCall.compareAndSet(call, null));
    }

    private CompletionStage<PersistedBatch> persistBatch(
            String expectedCursor, io.github.wxbot.ilink.api.message.UpdateBatch batch) {
        Instant started = clock.instant();
        CompletionStage<PersistedBatch> persisted;
        if (leaseOwnerId == null) {
            persisted = inboxStore.persistBatch(clientKey, expectedCursor, batch, CLAIM_TIMEOUT);
        } else {
            persisted = ((FencedInboxStore) inboxStore).persistBatchWhileLeaseHeld(
                    clientKey, leaseOwnerId, clock.instant(), expectedCursor, batch, CLAIM_TIMEOUT);
        }
        return persisted.whenComplete((ignored, failure) -> {
            if (failure == null) {
                metrics.recordDuration("ilink.cursor.commit.duration",
                        nonNegativeDuration(started, clock.instant()), Map.of());
            }
        });
    }

    /** @return 最近一次成功持久化新游标的时间 */
    public Instant cursorUpdatedAt() {
        return cursorUpdatedAt.get();
    }

    /**
     * 从 inbox 恢复未确认消息，通常在客户端启动或队列背压解除后调用。
     *
     * @param limit 最多读取数量
     * @return 成功进入分发器的数量
     */
    public CompletionStage<Integer> dispatchPending(int limit) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(0);
        }
        return inboxStore.claimPending(clientKey, clock.instant(), limit, CLAIM_TIMEOUT)
                .thenApply(this::dispatchMessages)
                .whenComplete((ignored, failure) -> refreshBacklog());
    }

    /** @return 分发器仍能接收消息，可以继续网络拉取 */
    public boolean hasDispatchCapacity() {
        return !closed.get() && dispatcher.hasCapacity();
    }

    private int dispatchPersisted(PersistedBatch batch) {
        for (StoredMessage stored : batch.acceptedMessages()) {
            contextManager.update(stored.message());
        }
        metrics.gauge("ilink.context.count", contextManager.size(), Map.of());
        return dispatchMessages(batch.acceptedMessages());
    }

    private int dispatchMessages(List<StoredMessage> messages) {
        int accepted = 0;
        for (StoredMessage message : messages) {
            if (!dispatcher.dispatch(message)) {
                inboxStore.release(clientKey, message.message().messageId())
                        .toCompletableFuture().join();
                for (int remaining = accepted + 1; remaining < messages.size(); remaining++) {
                    StoredMessage undispatched = messages.get(remaining);
                    inboxStore.release(clientKey, undispatched.message().messageId())
                            .toCompletableFuture().join();
                }
                break;
            }
            accepted++;
        }
        return accepted;
    }

    /** 异步刷新积压缓存；统计失败时保留最近一次成功值。 */
    public void refreshBacklog() {
        inboxStore.countPending(clientKey).whenComplete((value, failure) -> {
            if (failure == null) {
                backlogUpdater.accept(value);
                metrics.gauge("ilink.inbox.backlog", value, Map.of());
            } else if (!closed.get()) {
                LOGGER.warn("刷新收件箱积压失败，clientKey={}，failureType={}",
                        safeKey(clientKey), unwrap(failure).getClass().getSimpleName());
            }
        });
    }

    private static Duration nonNegativeDuration(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    /** 停止接受新拉取，并取消当前协议长轮询。 */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<?> call = activeProtocolCall.getAndSet(null);
        if (call != null) {
            call.cancel(true);
        }
        LOGGER.info("消息拉取器已经关闭，clientKey={}", safeKey(clientKey));
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException
                && failure.getCause() != null ? failure.getCause() : failure;
    }

    private void logPollFailure(Throwable failure) {
        if (failure instanceof TransportException transport) {
            LOGGER.warn("消息拉取失败，clientKey={}，errorCode={}，endpoint={}，httpStatus={}，"
                            + "protocolCode={}，retryable={}，failureType={}，causeType={}",
                    safeKey(clientKey), transport.errorCode(), transport.endpoint(),
                    transport.httpStatus(), transport.protocolCode(), transport.retryable(),
                    transport.getClass().getSimpleName(),
                    transport.getCause() == null ? null
                            : transport.getCause().getClass().getSimpleName());
            return;
        }
        if (failure instanceof ILinkException ilink) {
            LOGGER.warn("消息拉取失败，clientKey={}，errorCode={}，retryable={}，failureType={}",
                    safeKey(clientKey), ilink.errorCode(), ilink.retryable(),
                    failure.getClass().getSimpleName());
            return;
        }
        LOGGER.warn("消息拉取失败，clientKey={}，failureType={}",
                safeKey(clientKey), failure.getClass().getSimpleName());
    }

    /** 日志只展示客户端隔离键尾部。 */
    private static String safeKey(String value) {
        return value.length() <= 8 ? "***" : "***" + value.substring(value.length() - 8);
    }
}
