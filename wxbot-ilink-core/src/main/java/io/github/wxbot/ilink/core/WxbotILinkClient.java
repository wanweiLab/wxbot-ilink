/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core;

import io.github.wxbot.ilink.api.ILinkClient;
import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.message.InboxStore;
import io.github.wxbot.ilink.api.message.FencedInboxStore;
import io.github.wxbot.ilink.api.message.MessageHandler;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.observability.ClientHealth;
import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.api.observability.TracingSink;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.LeaseStore;
import io.github.wxbot.ilink.api.session.StateStore;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateListener;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.api.transport.UpdateProtocol;
import io.github.wxbot.ilink.core.context.ConversationContextManager;
import io.github.wxbot.ilink.core.lifecycle.ConnectionSupervisor;
import io.github.wxbot.ilink.core.lifecycle.LeaseGuard;
import io.github.wxbot.ilink.core.login.LoginController;
import io.github.wxbot.ilink.core.message.MessageSender;
import io.github.wxbot.ilink.core.message.ReliableMessagePublisher;
import io.github.wxbot.ilink.core.message.StripedMessageDispatcher;
import io.github.wxbot.ilink.core.message.UpdateLoop;
import io.github.wxbot.ilink.core.message.UpdatePoller;
import io.github.wxbot.ilink.core.retry.AsyncRetryExecutor;
import io.github.wxbot.ilink.core.retry.RetryPolicy;
import io.github.wxbot.ilink.core.retry.RetryBudget;
import io.github.wxbot.ilink.core.retry.CircuitBreaker;
import io.github.wxbot.ilink.core.state.ClientStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认 wxbot-ilink 客户端实现和构建入口。
 *
 * <p>构建器显式接收协议和存储 SPI，便于核心层测试及业务替换。一般使用方将在 HTTP 模块完成后通过预配置的
 * 工厂创建客户端，而无需逐项提供 SPI。
 */
public final class WxbotILinkClient implements ILinkClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(WxbotILinkClient.class);

    private final String clientKey;
    private final ILinkClientConfig config;
    private final StateStore stateStore;
    private final InboxStore inboxStore;
    private final LeaseStore leaseStore;
    private final String leaseOwnerId;
    private final UpdateProtocol updateProtocol;
    private final MessageProtocol messageProtocol;
    private final MessageHandler messageHandler;
    private final ReliableMessagePublisher messagePublisher;
    private final Clock clock;
    private final MetricsSink metrics;
    private final TracingSink tracing;
    private final ScheduledExecutorService scheduler;
    private final ClientStateMachine stateMachine;
    private final LoginController loginController;
    private final ConversationContextManager contexts;
    private final AtomicReference<RuntimeComponents> runtime = new AtomicReference<>();
    private final AtomicReference<BotSession> session = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private WxbotILinkClient(Builder builder) {
        this.clientKey = required(builder.clientKey, "客户端唯一键");
        this.config = Objects.requireNonNull(builder.config, "客户端配置不能为空");
        this.stateStore = Objects.requireNonNull(builder.stateStore, "状态存储不能为空");
        this.inboxStore = Objects.requireNonNull(builder.inboxStore, "收件箱不能为空");
        this.leaseStore = builder.leaseStore;
        this.leaseOwnerId = builder.leaseOwnerId == null
                ? UUID.randomUUID().toString() : required(builder.leaseOwnerId, "租约所有者标识");
        if (leaseStore != null && (leaseStore != inboxStore || !(inboxStore instanceof FencedInboxStore))) {
            throw new IllegalArgumentException("多实例模式要求租约存储与支持围栏的收件箱使用同一实现实例");
        }
        this.updateProtocol = Objects.requireNonNull(builder.updateProtocol, "消息拉取协议不能为空");
        this.messageProtocol = Objects.requireNonNull(builder.messageProtocol, "消息发送协议不能为空");
        this.messagePublisher = new ReliableMessagePublisher();
        this.messageHandler = builder.messageHandler == null
                ? messagePublisher : builder.messageHandler;
        this.clock = Objects.requireNonNull(builder.clock, "时钟不能为空");
        this.metrics = Objects.requireNonNull(builder.metrics, "指标出口不能为空");
        this.tracing = Objects.requireNonNull(builder.tracing, "链路追踪出口不能为空");
        this.scheduler = Executors.newScheduledThreadPool(2, daemonThreadFactory());
        this.stateMachine = new ClientStateMachine(clock, ignored -> { });
        this.contexts = new ConversationContextManager(clock, config.contextTtl());
        this.loginController = new LoginController(
                config,
                Objects.requireNonNull(builder.loginProtocol, "登录协议不能为空"),
                stateMachine,
                scheduler,
                clock);
    }

    /** @return 客户端构建器 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ClientState state() {
        return stateMachine.current();
    }

    @Override
    public ClientHealth health() {
        RuntimeComponents components = runtime.get();
        return new ClientHealth(
                stateMachine.current(),
                clock.instant(),
                components == null ? null : components.supervisor.lastSuccess(),
                components == null ? 0 : components.supervisor.consecutiveFailures(),
                components == null ? null : components.poller.cursorUpdatedAt(),
                components == null ? -1L : components.inboxBacklog.get(),
                components == null ? 0 : components.dispatcher.queuedTasks(),
                components == null ? null : components.supervisor.lastReconnect());
    }

    @Override
    public void addStateListener(ClientStateListener listener) {
        stateMachine.addListener(listener);
    }

    @Override
    public Flow.Publisher<io.github.wxbot.ilink.api.message.MessageDelivery> messages() {
        ensureOpen();
        return messagePublisher;
    }

    @Override
    public CompletionStage<LoginAttempt> login() {
        ensureOpen();
        LOGGER.info("客户端开始二维码登录，clientKey={}", safeKey(clientKey));
        return loginController.start().thenApply(attempt -> new LoginAttempt() {
            private final CompletionStage<BotSession> ready = attempt.completion().thenApply(loggedInSession -> {
                if (!closed.get()) {
                    startRuntime(loggedInSession);
                }
                LOGGER.info("客户端二维码登录成功，clientKey={}", safeKey(clientKey));
                return loggedInSession;
            });

            @Override
            public io.github.wxbot.ilink.api.login.QrCode qrCode() {
                return attempt.qrCode();
            }

            @Override
            public CompletionStage<BotSession> completion() {
                return ready;
            }

            @Override
            public boolean cancel() {
                return attempt.cancel();
            }
        });
    }

    @Override
    public CompletionStage<Boolean> restore() {
        CompletionStage<Optional<ClientSnapshot>> loading;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (stateMachine.current() != ClientState.NEW) {
                LOGGER.warn("拒绝重复恢复客户端，clientKey={}，state={}",
                        safeKey(clientKey), stateMachine.current());
                return CompletableFuture.failedFuture(
                        new IllegalStateException("只有新建客户端可以执行恢复"));
            }
            stateMachine.transitionTo(ClientState.RESTORING, "正在加载客户端快照");
            loading = stateStore.load(clientKey);
        }
        return loading.thenApply(optional -> {
            synchronized (lifecycleLock) {
                ensureOpen();
                if (optional.isEmpty()) {
                    stateMachine.transitionTo(ClientState.LOGIN_REQUIRED, "没有找到客户端快照");
                    LOGGER.info("未找到可恢复快照，clientKey={}", safeKey(clientKey));
                    return false;
                }
                ClientSnapshot snapshot = optional.get();
                contexts.restore(snapshot.conversations());
                stateMachine.transitionTo(ClientState.CONNECTED, "客户端快照恢复成功");
                startRuntime(snapshot.session());
                LOGGER.info("客户端快照恢复成功，clientKey={}", safeKey(clientKey));
                return true;
            }
        }).exceptionally(failure -> {
            synchronized (lifecycleLock) {
                if (!closed.get() && stateMachine.current() == ClientState.RESTORING) {
                    stateMachine.transitionTo(ClientState.LOGIN_REQUIRED, "客户端快照恢复失败");
                }
            }
            LOGGER.error("客户端快照恢复失败，clientKey={}", safeKey(clientKey), unwrap(failure));
            throw new java.util.concurrent.CompletionException(unwrap(failure));
        });
    }

    @Override
    public CompletionStage<SendReceipt> send(SendMessageRequest request) {
        return requireRuntime().sender.send(request);
    }

    @Override
    public CompletionStage<SendReceipt> sendWithTyping(
            SendMessageRequest request, Duration typingDuration) {
        return requireRuntime().sender.sendWithTyping(request, typingDuration);
    }

    @Override
    public CompletionStage<Optional<ClientSnapshot>> saveSnapshot() {
        ensureOpen();
        BotSession currentSession = session.get();
        if (currentSession == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return inboxStore.loadCursor(clientKey).thenCompose(cursor -> {
            ClientSnapshot snapshot = new ClientSnapshot(
                    ClientSnapshot.CURRENT_SCHEMA_VERSION,
                    currentSession,
                    cursor,
                    contexts.snapshot(),
                    Instant.now(clock));
            return stateStore.save(clientKey, snapshot).thenApply(ignored -> Optional.of(snapshot));
        });
    }

    @Override
    public void close() {
        long deadline = closeDeadline(config.closeTimeout());
        // 先封闭登录入口并等待其状态提交临界区退出，避免登录成功与关闭状态交错。
        loginController.close();
        RuntimeComponents components;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ClientState current = stateMachine.current();
            if (current != ClientState.CLOSING && current != ClientState.CLOSED) {
                stateMachine.transitionTo(ClientState.CLOSING, "客户端正在关闭");
            }
            messagePublisher.close();
            components = runtime.getAndSet(null);
        }
        if (components != null) {
            CompletionStage<Void> leaseRelease = components.closeRuntime();
            try {
                components.dispatcher.awaitTermination(remaining(deadline));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
            awaitStage(leaseRelease, deadline);
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
        if (stateMachine.current() == ClientState.CLOSING) {
            stateMachine.transitionTo(ClientState.CLOSED, "客户端资源已经释放");
        }
        LOGGER.info("客户端已经关闭，clientKey={}", safeKey(clientKey));
    }

    private static long closeDeadline(Duration timeout) {
        long now = System.nanoTime();
        long nanos = timeout.toNanos();
        return nanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos;
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(remainingNanos(deadline));
    }

    private static void awaitStage(CompletionStage<?> stage, long deadline) {
        try {
            stage.toCompletableFuture().get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ignored) {
            // 关闭期限优先；租约仍会由后端 TTL 最终回收。
        } catch (java.util.concurrent.ExecutionException ignored) {
            // 释放失败不阻止其余本地资源关闭。
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private void startRuntime(BotSession loggedInSession) {
        if (closed.get()) {
            return;
        }
        AtomicLong backlog = new AtomicLong(-1L);
        AtomicReference<UpdatePoller> pollerReference = new AtomicReference<>();
        Runnable refreshBacklog = () -> {
            UpdatePoller current = pollerReference.get();
            if (current != null) {
                current.refreshBacklog();
            }
        };
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                config.dispatchStripes(), config.dispatchQueueCapacity(), inboxStore, messageHandler,
                config.messageProcessingTimeout(), config.maxDeliveryAttempts(),
                config.acknowledgementMode(), clock, metrics, refreshBacklog);
        UpdatePoller poller = new UpdatePoller(
                clientKey, loggedInSession, updateProtocol, inboxStore, contexts, dispatcher, clock,
                leaseStore == null ? null : leaseOwnerId, metrics, backlog::set);
        pollerReference.set(poller);
        ConnectionSupervisor supervisor = new ConnectionSupervisor(stateMachine, 2, 5, clock);
        UpdateLoop loop = new UpdateLoop(
                poller, supervisor, stateMachine, scheduler,
                Duration.ofSeconds(1), Duration.ofSeconds(30), metrics, clock);
        MessageSender sender = new MessageSender(
                loggedInSession, messageProtocol, contexts,
                new AsyncRetryExecutor(
                        new RetryPolicy(config.maxAttempts(), Duration.ofMillis(250), Duration.ofSeconds(5)),
                        scheduler,
                        new RetryBudget(config.retryBudgetWindow(), config.retryBudgetRatio(),
                                config.retryBudgetMinimum(), config.retryBudgetMaximum(), clock),
                        new CircuitBreaker(config.circuitBreakerFailureThreshold(),
                                config.circuitBreakerOpenDuration(), clock)),
                scheduler, metrics, clock, tracing);
        RuntimeComponents created = new RuntimeComponents(
                dispatcher, poller, supervisor, loop, sender, backlog);
        if (leaseStore != null) {
            created.leaseGuard = new LeaseGuard(
                    leaseStore,
                    clientKey,
                    leaseOwnerId,
                    config.leaseTtl(),
                    config.leaseRenewInterval(),
                    scheduler,
                    clock,
                    () -> handleLeaseLost(created),
                    metrics);
        }
        boolean accepted;
        synchronized (lifecycleLock) {
            accepted = !closed.get() && runtime.compareAndSet(null, created);
            if (accepted) {
                session.set(loggedInSession);
                LOGGER.info("客户端运行组件已启动，clientKey={}，leaseEnabled={}",
                        safeKey(clientKey), created.leaseGuard != null);
                if (created.leaseGuard == null) {
                    created.ready.set(true);
                    poller.refreshBacklog();
                    loop.start();
                } else {
                    acquireRuntimeLease(created);
                }
            }
        }
        if (!accepted) {
            created.closeRuntime();
        }
    }

    private void acquireRuntimeLease(RuntimeComponents components) {
        if (closed.get() || runtime.get() != components) {
            return;
        }
        components.leaseGuard.acquire().whenComplete((acquired, failure) -> {
            synchronized (lifecycleLock) {
                if (closed.get() || runtime.get() != components) {
                    return;
                }
                if (failure == null && Boolean.TRUE.equals(acquired)) {
                    LOGGER.info("客户端运行租约获取成功，clientKey={}", safeKey(clientKey));
                    components.ready.set(true);
                    components.poller.refreshBacklog();
                    if (stateMachine.current() == ClientState.RECONNECTING) {
                        stateMachine.transitionTo(ClientState.CONNECTED, "已经取得客户端运行租约");
                    }
                    components.loop.start();
                    return;
                }
                if (failure != null) {
                    LOGGER.warn("客户端运行租约获取异常，clientKey={}",
                            safeKey(clientKey), unwrap(failure));
                } else {
                    LOGGER.info("客户端运行租约被其他实例占用，clientKey={}", safeKey(clientKey));
                }
                moveToReconnecting("客户端运行租约当前由其他实例持有");
                components.scheduleAcquireRetry(
                        () -> acquireRuntimeLease(components), config.leaseRenewInterval());
            }
        });
    }

    private void handleLeaseLost(RuntimeComponents components) {
        synchronized (lifecycleLock) {
            if (closed.get() || runtime.get() != components) {
                return;
            }
            components.ready.set(false);
            LOGGER.error("客户端运行租约丢失，停止消息拉取，clientKey={}", safeKey(clientKey));
            components.loop.close();
            moveToReconnecting("客户端运行租约已经丢失");
            components.scheduleAcquireRetry(
                    () -> acquireRuntimeLease(components), config.leaseRenewInterval());
        }
    }

    private void moveToReconnecting(String reason) {
        ClientState current = stateMachine.current();
        if (current == ClientState.CONNECTED || current == ClientState.DEGRADED) {
            stateMachine.transitionTo(ClientState.RECONNECTING, reason);
        }
    }

    private RuntimeComponents requireRuntime() {
        ensureOpen();
        RuntimeComponents components = runtime.get();
        if (components == null || !components.ready.get()
                || stateMachine.current() != ClientState.CONNECTED) {
            throw new IllegalStateException("客户端尚未连接");
        }
        return components;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ClientClosedException("客户端");
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "wxbot-ilink-scheduler");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /** 日志只展示隔离键尾部，避免完整业务映射进入日志。 */
    private static String safeKey(String value) {
        return value == null || value.length() <= 8
                ? "***" : "***" + value.substring(value.length() - 8);
    }

    private final class RuntimeComponents {
        private final StripedMessageDispatcher dispatcher;
        private final UpdatePoller poller;
        private final ConnectionSupervisor supervisor;
        private final UpdateLoop loop;
        private final MessageSender sender;
        private final AtomicLong inboxBacklog;
        private final AtomicBoolean ready = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> leaseAcquireTask = new AtomicReference<>();
        private LeaseGuard leaseGuard;

        private RuntimeComponents(
                StripedMessageDispatcher dispatcher,
                UpdatePoller poller,
                ConnectionSupervisor supervisor,
                UpdateLoop loop,
                MessageSender sender,
                AtomicLong inboxBacklog) {
            this.dispatcher = dispatcher;
            this.poller = poller;
            this.supervisor = supervisor;
            this.loop = loop;
            this.sender = sender;
            this.inboxBacklog = inboxBacklog;
        }

        private void scheduleAcquireRetry(Runnable action, Duration delay) {
            if (closed.get() || runtime.get() != this) {
                return;
            }
            ScheduledFuture<?> next;
            try {
                next = scheduler.schedule(action, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // 客户端关闭与失租回调可能交错；关闭中的调度拒绝不应逃逸到存储回调线程。
                return;
            }
            ScheduledFuture<?> previous = leaseAcquireTask.getAndSet(next);
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
            if (closed.get() || runtime.get() != this) {
                if (leaseAcquireTask.compareAndSet(next, null)) {
                    next.cancel(false);
                }
            }
        }

        private CompletionStage<Void> closeRuntime() {
            ready.set(false);
            ScheduledFuture<?> acquireTask = leaseAcquireTask.getAndSet(null);
            if (acquireTask != null) {
                acquireTask.cancel(false);
            }
            loop.close();
            poller.close();
            sender.close();
            if (leaseGuard != null) {
                CompletionStage<Void> released = leaseGuard.closeAsync();
                dispatcher.close();
                return released;
            }
            dispatcher.close();
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 客户端构建器。 */
    public static final class Builder {
        private String clientKey;
        private ILinkClientConfig config = ILinkClientConfig.builder().build();
        private LoginProtocol loginProtocol;
        private UpdateProtocol updateProtocol;
        private MessageProtocol messageProtocol;
        private StateStore stateStore;
        private InboxStore inboxStore;
        private LeaseStore leaseStore;
        private String leaseOwnerId;
        private MessageHandler messageHandler;
        private Clock clock = Clock.systemUTC();
        private MetricsSink metrics = MetricsSink.noop();
        private TracingSink tracing = TracingSink.noop();

        private Builder() {
        }

        public Builder clientKey(String value) {
            this.clientKey = value;
            return this;
        }

        public Builder config(ILinkClientConfig value) {
            this.config = value;
            return this;
        }

        public Builder loginProtocol(LoginProtocol value) {
            this.loginProtocol = value;
            return this;
        }

        public Builder updateProtocol(UpdateProtocol value) {
            this.updateProtocol = value;
            return this;
        }

        public Builder messageProtocol(MessageProtocol value) {
            this.messageProtocol = value;
            return this;
        }

        /**
         * 一次设置登录、拉取和发送协议。
         *
         * <p>真实 HTTP 实现通常同时实现三个接口；该方法避免调用方重复书写三个 setter。
         */
        public Builder protocols(
                LoginProtocol login, UpdateProtocol update, MessageProtocol message) {
            this.loginProtocol = login;
            this.updateProtocol = update;
            this.messageProtocol = message;
            return this;
        }

        public Builder stateStore(StateStore value) {
            this.stateStore = value;
            return this;
        }

        public Builder inboxStore(InboxStore value) {
            this.inboxStore = value;
            return this;
        }

        /**
         * 启用多实例运行租约。
         *
         * <p>未配置时客户端保持单进程模式；生产环境存在多个副本时应配置共享租约存储。
         */
        public Builder leaseStore(LeaseStore value) {
            this.leaseStore = value;
            return this;
        }

        /**
         * 设置当前运行实例的稳定唯一标识。
         *
         * <p>未设置时为客户端对象生成随机标识。容器环境推荐使用 Pod UID，而不是可重复的主机名。
         */
        public Builder leaseOwnerId(String value) {
            this.leaseOwnerId = value;
            return this;
        }

        public Builder messageHandler(MessageHandler value) {
            this.messageHandler = Objects.requireNonNull(value, "消息处理器不能为空");
            return this;
        }

        public Builder clock(Clock value) {
            this.clock = value;
            return this;
        }

        /** 配置非阻塞指标出口。 */
        public Builder metrics(MetricsSink value) {
            this.metrics = value;
            return this;
        }

        /** 配置可选链路追踪出口。 */
        public Builder tracing(TracingSink value) {
            this.tracing = value;
            return this;
        }

        /** 构建尚未登录或恢复的客户端。 */
        public WxbotILinkClient build() {
            return new WxbotILinkClient(this);
        }
    }
}
