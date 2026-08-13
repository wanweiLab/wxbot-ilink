/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.exception.ContextMissingException;
import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.message.ContextReference;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.api.observability.TracingSink;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import io.github.wxbot.ilink.core.retry.AsyncRetryExecutor;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一处理上下文解析、幂等重试和输入态的消息发送器。
 *
 * <p>重试复用调用方请求中的同一个 {@code clientId}。输入态延迟通过调度器实现，不占用工作线程等待。
 */
public final class MessageSender implements AutoCloseable {

    private final BotSession session;
    private final MessageProtocol protocol;
    private final ConversationContextManager contexts;
    private final AsyncRetryExecutor retryExecutor;
    private final ScheduledExecutorService scheduler;
    private final MetricsSink metrics;
    private final Clock clock;
    private final TracingSink tracing;
    private final ConcurrentMap<String, String> typingTickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Void>> userSendTails = new ConcurrentHashMap<>();
    private final Set<OrderedResult<?>> activeSends = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public MessageSender(
            BotSession session,
            MessageProtocol protocol,
            ConversationContextManager contexts,
            AsyncRetryExecutor retryExecutor,
            ScheduledExecutorService scheduler) {
        this(session, protocol, contexts, retryExecutor, scheduler, MetricsSink.noop(), Clock.systemUTC());
    }

    /** 创建带指标记录的消息发送器。 */
    public MessageSender(
            BotSession session,
            MessageProtocol protocol,
            ConversationContextManager contexts,
            AsyncRetryExecutor retryExecutor,
            ScheduledExecutorService scheduler,
            MetricsSink metrics,
            Clock clock) {
        this(session, protocol, contexts, retryExecutor, scheduler, metrics, clock, TracingSink.noop());
    }

    /** 创建带指标和链路追踪出口的消息发送器。 */
    public MessageSender(
            BotSession session,
            MessageProtocol protocol,
            ConversationContextManager contexts,
            AsyncRetryExecutor retryExecutor,
            ScheduledExecutorService scheduler,
            MetricsSink metrics,
            Clock clock,
            TracingSink tracing) {
        this.session = Objects.requireNonNull(session, "Bot 会话不能为空");
        this.protocol = Objects.requireNonNull(protocol, "消息协议不能为空");
        this.contexts = Objects.requireNonNull(contexts, "上下文管理器不能为空");
        this.retryExecutor = Objects.requireNonNull(retryExecutor, "重试执行器不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不能为空");
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.tracing = Objects.requireNonNull(tracing, "链路追踪出口不能为空");
    }

    /** 解析上下文并异步发送消息。 */
    public CompletionStage<SendReceipt> send(SendMessageRequest request) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ClientClosedException("消息发送器"));
        }
        SendMessageRequest resolved = resolveContext(request);
        return ordered(resolved.toUserId(), () -> sendResolved(resolved));
    }

    private CompletionStage<SendReceipt> sendResolved(SendMessageRequest resolved) {
        Instant started = clock.instant();
        TracingSink.Span span = tracing.start(
                "ilink.send", Map.of("message.type", resolved.type().name()));
        CompletionStage<SendReceipt> operation;
        try {
            operation = retryExecutor.execute(() -> protocol.send(session, resolved));
        } catch (Throwable failure) {
            recordSend(started, failure);
            finishSpan(span, failure);
            return CompletableFuture.failedFuture(failure);
        }
        operation.whenComplete((receipt, failure) -> {
            recordSend(started, failure);
            finishSpan(span, failure);
        });
        return operation;
    }

    /**
     * 展示输入态一段时间后发送消息，并在任何发送结果下尝试关闭输入态。
     *
     * @param request 发送请求
     * @param typingDuration 输入态持续时间
     */
    public CompletionStage<SendReceipt> sendWithTyping(
            SendMessageRequest request, Duration typingDuration) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ClientClosedException("消息发送器"));
        }
        if (typingDuration == null || typingDuration.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("输入态持续时间不能为负数"));
        }
        SendMessageRequest resolved = resolveContext(request);
        return ordered(resolved.toUserId(), () -> sendWithTypingResolved(resolved, typingDuration));
    }

    private CompletionStage<SendReceipt> sendWithTypingResolved(
            SendMessageRequest resolved, Duration typingDuration) {
        Instant started = clock.instant();
        TracingSink.Span span = tracing.start(
                "ilink.send_with_typing", Map.of("message.type", resolved.type().name()));
        TypingSendOperation operation = new TypingSendOperation(resolved, typingDuration);
        operation.whenComplete((receipt, failure) -> {
            recordSend(started, failure);
            finishSpan(span, failure);
        });
        try {
            operation.start();
        } catch (Throwable failure) {
            operation.completeExceptionally(failure);
        }
        return operation;
    }

    /**
     * 按目标用户串行发送完整操作，同时允许不同用户并行。
     *
     * <p>尾阶段先原子发布到映射，再在 {@code compute} 返回后注册条件删除。这样即使操作同步完成，清理也不会
     * 早于映射发布，更不会在 {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} 内递归修改。
     */
    private <T> CompletionStage<T> ordered(
            String userId, Supplier<CompletionStage<T>> operation) {
        OrderedResult<T> result = new OrderedResult<>();
        CompletableFuture<Void> startSignal = new CompletableFuture<>();
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new ClientClosedException("消息发送器"));
            }
            activeSends.add(result);
            result.whenComplete((ignored, failure) -> activeSends.remove(result));
            CompletableFuture<Void> tail = userSendTails.compute(userId, (key, previous) -> {
                CompletableFuture<Void> predecessor = previous == null
                        ? CompletableFuture.completedFuture(null) : previous;
                return predecessor
                        .handle((ignored, failure) -> null)
                        .thenCompose(ignored -> startSignal)
                        .thenCompose(ignored -> runOrdered(operation, result));
            });
            tail.whenComplete((ignored, failure) -> userSendTails.remove(userId, tail));
        }
        // 在生命周期锁之外启动协议调用，避免同步 SPI 阻塞关闭临界区。
        startSignal.complete(null);
        return result;
    }

    private static <T> CompletionStage<Void> runOrdered(
            Supplier<CompletionStage<T>> operation, OrderedResult<T> result) {
        if (result.isCancelled()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<T> current;
        try {
            current = Objects.requireNonNull(operation.get(), "发送操作不能返回空阶段")
                    .toCompletableFuture();
            result.attach(current);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return CompletableFuture.completedFuture(null);
        }
        return current.handle((value, failure) -> {
            result.detach(current);
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(unwrap(failure));
            }
            return null;
        });
    }

    private void recordSend(Instant started, Throwable failure) {
        metrics.recordDuration("ilink.send.duration",
                Duration.between(started, clock.instant()), Map.of());
        if (failure != null) {
            metrics.increment("ilink.send.failures", Map.of());
        }
    }

    private static void finishSpan(TracingSink.Span span, Throwable failure) {
        if (failure != null) {
            span.error(failure);
        }
        span.close();
    }

    private SendMessageRequest resolveContext(SendMessageRequest request) {
        Objects.requireNonNull(request, "发送请求不能为空");
        String token;
        token = switch (request.context().mode()) {
            case EXPLICIT -> request.context().value();
            case LATEST -> contexts.find(request.toUserId())
                    .orElseThrow(() -> new ContextMissingException(request.toUserId()))
                    .contextToken();
            case FROM_MESSAGE -> contexts.findByMessageId(parseMessageId(request.context().value()))
                    .orElseThrow(() -> new ContextMissingException(request.toUserId()))
                    .contextToken();
        };
        return new SendMessageRequest(
                request.clientId(), request.toUserId(), request.type(),
                ContextReference.explicit(token), request.payload());
    }

    private static long parseMessageId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("来源消息标识格式无效", failure);
        }
    }

    private CompletionStage<String> ensureTypingTicket(String userId, String contextToken) {
        String cached = typingTickets.get(userId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<String> source = protocol
                .requestTypingTicket(session, userId, contextToken).toCompletableFuture();
        CompletableFuture<String> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    source.cancel(mayInterruptIfRunning);
                }
                return cancelled;
            }
        };
        source.whenComplete((ticket, failure) -> {
            if (failure != null) {
                result.completeExceptionally(unwrap(failure));
            } else if (ticket == null || ticket.isBlank()) {
                result.completeExceptionally(new IllegalStateException("服务端返回了空输入态票据"));
            } else {
                String previous = typingTickets.putIfAbsent(userId, ticket);
                result.complete(previous == null ? ticket : previous);
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /** 拒绝后续发送并取消所有排队或在途请求。 */
    @Override
    public void close() {
        Set<OrderedResult<?>> sends;
        Set<CompletableFuture<Void>> tails;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            sends = Set.copyOf(activeSends);
            tails = Set.copyOf(userSendTails.values());
            userSendTails.clear();
        }
        sends.forEach(result -> result.cancel(true));
        tails.forEach(tail -> tail.cancel(true));
    }

    /** 显式编排输入态、延迟、发送和清理，使取消可以穿透每一个阶段。 */
    private final class TypingSendOperation extends CompletableFuture<SendReceipt> {
        private final SendMessageRequest request;
        private final Duration typingDuration;
        private final AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> delayed = new AtomicReference<>();
        private final AtomicReference<String> ticket = new AtomicReference<>();
        private final AtomicBoolean typingStarted = new AtomicBoolean();
        private final AtomicBoolean typingStartInFlight = new AtomicBoolean();
        private final AtomicBoolean stopStarted = new AtomicBoolean();

        private TypingSendOperation(SendMessageRequest request, Duration typingDuration) {
            this.request = request;
            this.typingDuration = typingDuration;
        }

        private void start() {
            CompletableFuture<String> ticketFuture = ensureTypingTicket(
                    request.toUserId(), request.context().value()).toCompletableFuture();
            attach(ticketFuture);
            ticketFuture.whenComplete((resolvedTicket, failure) -> {
                detach(ticketFuture);
                if (isDone()) {
                    return;
                }
                if (failure != null) {
                    completeExceptionally(unwrap(failure));
                    return;
                }
                ticket.set(resolvedTicket);
                startTyping(resolvedTicket);
            });
        }

        private void startTyping(String resolvedTicket) {
            // 一旦开始请求存在不确定结果，取消路径就必须尽力发送关闭输入态请求。
            typingStarted.set(true);
            typingStartInFlight.set(true);
            CompletableFuture<Void> typingFuture;
            try {
                typingFuture = protocol.setTyping(
                        session, request.toUserId(), resolvedTicket, true).toCompletableFuture();
            } catch (Throwable failure) {
                typingStartInFlight.set(false);
                stopTyping(null, failure);
                return;
            }
            attach(typingFuture);
            typingFuture.whenComplete((ignored, failure) -> {
                detach(typingFuture);
                typingStartInFlight.set(false);
                if (failure != null) {
                    stopTyping(null, unwrap(failure));
                    return;
                }
                if (isDone()) {
                    stopTyping(null, null);
                } else {
                    scheduleSend();
                }
            });
        }

        private void scheduleSend() {
            try {
                ScheduledFuture<?> waiting = scheduler.schedule(
                        this::sendAfterDelay, typingDuration.toMillis(), TimeUnit.MILLISECONDS);
                delayed.set(waiting);
                if (isDone() && delayed.compareAndSet(waiting, null)) {
                    waiting.cancel(false);
                    stopTyping(null, null);
                }
            } catch (RejectedExecutionException failure) {
                stopTyping(null, failure);
            }
        }

        private void sendAfterDelay() {
            delayed.set(null);
            if (isDone()) {
                stopTyping(null, null);
                return;
            }
            CompletableFuture<SendReceipt> sendFuture;
            try {
                sendFuture = retryExecutor.execute(
                        () -> protocol.send(session, request)).toCompletableFuture();
            } catch (Throwable failure) {
                stopTyping(null, failure);
                return;
            }
            attach(sendFuture);
            sendFuture.whenComplete((receipt, failure) -> {
                detach(sendFuture);
                if (isCancelled()) {
                    stopTyping(null, null);
                } else {
                    stopTyping(receipt, failure == null ? null : unwrap(failure));
                }
            });
        }

        private void stopTyping(SendReceipt receipt, Throwable primaryFailure) {
            if (!typingStarted.get()) {
                finish(receipt, primaryFailure);
                return;
            }
            if (!stopStarted.compareAndSet(false, true)) {
                return;
            }
            CompletableFuture<Void> stopFuture;
            try {
                stopFuture = protocol.setTyping(
                        session, request.toUserId(), ticket.get(), false).toCompletableFuture();
            } catch (Throwable stopFailure) {
                finish(receipt, primaryFailure);
                return;
            }
            stopFuture.whenComplete((ignored, stopFailure) -> {
                if (stopFailure != null) {
                    metrics.increment("ilink.typing.stop.failures", Map.of());
                }
                finish(receipt, primaryFailure);
            });
        }

        private void finish(SendReceipt receipt, Throwable failure) {
            if (isDone()) {
                return;
            }
            if (failure == null) {
                complete(receipt);
            } else {
                completeExceptionally(failure);
            }
        }

        private void attach(CompletableFuture<?> value) {
            current.set(value);
            if (isCancelled()) {
                if (!typingStartInFlight.get()) {
                    value.cancel(true);
                }
                if (!typingStartInFlight.get()) {
                    stopTyping(null, null);
                }
            }
        }

        private void detach(CompletableFuture<?> value) {
            current.compareAndSet(value, null);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (!cancelled) {
                return false;
            }
            CompletableFuture<?> active = current.getAndSet(null);
            // 输入态启动请求存在“服务端已生效、客户端尚未收到响应”的不确定窗口，
            // 保留该阶段完成回调以便随后发送关闭输入态；其他阶段可直接取消。
            if (active != null && !typingStartInFlight.get()) {
                active.cancel(mayInterruptIfRunning);
            }
            ScheduledFuture<?> waiting = delayed.getAndSet(null);
            if (waiting != null) {
                waiting.cancel(false);
            }
            if (!typingStartInFlight.get()) {
                stopTyping(null, null);
            }
            return true;
        }
    }

    /** 取消外层发送结果时继续取消当前重试或协议阶段。 */
    private static final class OrderedResult<T> extends CompletableFuture<T> {
        private final AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();

        private void attach(CompletableFuture<?> value) {
            current.set(value);
            if (isCancelled()) {
                value.cancel(true);
            }
        }

        private void detach(CompletableFuture<?> value) {
            current.compareAndSet(value, null);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            CompletableFuture<?> active = current.getAndSet(null);
            if (cancelled && active != null) {
                active.cancel(mayInterruptIfRunning);
            }
            return cancelled;
        }
    }
}
