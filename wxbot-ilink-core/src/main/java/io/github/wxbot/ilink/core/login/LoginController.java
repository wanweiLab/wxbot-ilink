/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.login;

import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.api.exception.ILinkException;
import io.github.wxbot.ilink.api.exception.LoginTimeoutException;
import io.github.wxbot.ilink.api.exception.QrCodeExpiredException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.login.LoginPhase;
import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.core.state.ClientStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 负责二维码创建、限频状态查询、超时和取消的登录控制器。
 *
 * <p>协议调用由 {@link LoginProtocol} 执行，控制器只编排流程。任意时刻只允许一个登录任务，避免旧二维码任务
 * 在新任务开始后错误覆盖客户端会话。
 */
public final class LoginController implements AutoCloseable {

    private static final long MAX_POLL_FAILURE_BACKOFF_MILLIS = 5_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(LoginController.class);

    private final ILinkClientConfig config;
    private final LoginProtocol protocol;
    private final ClientStateMachine stateMachine;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final AtomicReference<Attempt> activeAttempt = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<QrCode>> qrRequest = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    public LoginController(
            ILinkClientConfig config,
            LoginProtocol protocol,
            ClientStateMachine stateMachine,
            ScheduledExecutorService scheduler,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "客户端配置不能为空");
        this.protocol = Objects.requireNonNull(protocol, "登录协议不能为空");
        this.stateMachine = Objects.requireNonNull(stateMachine, "状态机不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 异步创建二维码并启动登录状态轮询。
     *
     * @return 登录任务创建阶段
     */
    public CompletionStage<LoginAttempt> start() {
        CompletableFuture<QrCode> request;
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new ClientClosedException("登录控制器"));
            }
            if (activeAttempt.get() != null || qrRequest.get() != null) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("已经存在进行中的登录任务"));
            }

            ClientState current = stateMachine.current();
            if (current == ClientState.NEW) {
                stateMachine.transitionTo(ClientState.LOGIN_REQUIRED, "未发现可恢复会话");
            } else if (current != ClientState.LOGIN_REQUIRED) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("当前状态不能启动登录：" + current));
            }
            try {
                request = protocol.requestQrCode().toCompletableFuture();
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
            qrRequest.set(request);
        }
        return request.handle((qrCode, failure) -> {
            synchronized (lifecycleLock) {
                qrRequest.compareAndSet(request, null);
                if (failure != null) {
                    throw new CompletionException(unwrap(failure));
                }
                if (closed.get()) {
                    throw new ClientClosedException("登录控制器");
                }
                Attempt attempt = new Attempt(qrCode);
                if (!activeAttempt.compareAndSet(null, attempt)) {
                    throw new IllegalStateException("已经存在进行中的登录任务");
                }
                stateMachine.transitionTo(ClientState.QR_WAITING, "登录二维码已生成");
                LOGGER.info("登录二维码已生成，等待用户扫码确认");
                attempt.scheduleNext(0L);
                return attempt;
            }
        });
    }

    @Override
    public void close() {
        CompletableFuture<QrCode> request;
        Attempt attempt;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            request = qrRequest.getAndSet(null);
            attempt = activeAttempt.getAndSet(null);
        }
        if (request != null) {
            request.cancel(true);
        }
        if (attempt != null) {
            attempt.cancel();
        }
    }

    private final class Attempt implements LoginAttempt {

        private final QrCode qrCode;
        private final Instant deadline;
        private final CompletableFuture<BotSession> completion = new CompletableFuture<>();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
        private final AtomicLong scheduleGeneration = new AtomicLong();
        private int consecutivePollFailures;

        private Attempt(QrCode qrCode) {
            this.qrCode = Objects.requireNonNull(qrCode, "二维码不能为空");
            Instant configuredDeadline = clock.instant().plus(config.loginTimeout());
            this.deadline = qrCode.expiresAt().isBefore(configuredDeadline)
                    ? qrCode.expiresAt() : configuredDeadline;
        }

        @Override
        public QrCode qrCode() {
            return qrCode;
        }

        @Override
        public CompletionStage<BotSession> completion() {
            return completion;
        }

        @Override
        public boolean cancel() {
            synchronized (lifecycleLock) {
                if (!finished.compareAndSet(false, true)) {
                    return false;
                }
                cancelScheduled();
                cancelInFlight();
                activeAttempt.compareAndSet(this, null);
                if (!closed.get() && (stateMachine.current() == ClientState.QR_WAITING
                        || stateMachine.current() == ClientState.QR_SCANNED)) {
                    stateMachine.transitionTo(ClientState.LOGIN_REQUIRED, "登录任务已取消");
                }
            }
            completion.completeExceptionally(new CancellationException("登录任务已取消"));
            LOGGER.info("二维码登录任务已取消");
            return true;
        }

        private void scheduleNext(long delayMillis) {
            if (finished.get()) {
                return;
            }
            long generation = scheduleGeneration.incrementAndGet();
            ScheduledFuture<?> next;
            try {
                next = scheduler.schedule(this::poll, delayMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException failure) {
                fail(new ClientClosedException("登录控制器"), "登录调度器已经关闭", false);
                return;
            }
            // 零延迟任务可能在句柄登记前执行并安排下一轮；旧代次不得覆盖或取消新任务。
            if (generation != scheduleGeneration.get() || finished.get()) {
                next.cancel(false);
                return;
            }
            ScheduledFuture<?> previous = scheduled.getAndSet(next);
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
        }

        private void poll() {
            if (finished.get()) {
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                fail(new LoginTimeoutException(), "二维码登录超时", true);
                return;
            }

            CompletableFuture<LoginPollResult> request;
            try {
                request = protocol.queryQrCodeStatus(qrCode.token()).toCompletableFuture();
            } catch (Throwable failure) {
                handlePollFailure(unwrap(failure));
                return;
            }
            inFlight.set(request);
            request.whenComplete((result, failure) -> {
                inFlight.compareAndSet(request, null);
                if (finished.get()) {
                    return;
                }
                try {
                    if (failure != null) {
                        handlePollFailure(unwrap(failure));
                        return;
                    }
                    consecutivePollFailures = 0;
                    handle(result);
                } catch (Throwable callbackFailure) {
                    // CompletableFuture 会把回调异常保存在无人持有的新阶段中，必须显式结束登录任务。
                    fail(unwrap(callbackFailure), "登录状态处理失败", false);
                }
            });
        }

        /**
         * 对明确标记为可重试的网络故障执行有限退避，避免一次读超时直接作废用户已经扫描的二维码。
         * 登录总时限和二维码有效期仍是硬边界，不可重试的认证或协议错误会立即失败。
         */
        private void handlePollFailure(Throwable failure) {
            if (!(failure instanceof ILinkException ilinkFailure) || !ilinkFailure.retryable()) {
                fail(failure, "登录状态查询失败", false);
                return;
            }
            if (!clock.instant().isBefore(deadline)) {
                fail(new LoginTimeoutException(), "二维码登录超时", true);
                return;
            }
            consecutivePollFailures = Math.min(consecutivePollFailures + 1, 30);
            long delay = pollFailureDelayMillis(failure);
            LOGGER.warn("登录状态查询暂时失败，将退避重试，failureType={}，failureCount={}，delayMs={}",
                    failure.getClass().getSimpleName(), consecutivePollFailures, delay);
            scheduleNext(delay);
        }

        private long pollFailureDelayMillis(Throwable failure) {
            long base = Math.max(1L, config.loginPollInterval().toMillis());
            int shift = Math.min(consecutivePollFailures - 1, 20);
            long exponential = base > (Long.MAX_VALUE >> shift)
                    ? Long.MAX_VALUE : base << shift;
            long delay = Math.min(exponential, MAX_POLL_FAILURE_BACKOFF_MILLIS);
            long jitterMinimum = Math.max(1L, delay / 2L);
            delay = jitterMinimum >= delay
                    ? delay : ThreadLocalRandom.current().nextLong(jitterMinimum, delay + 1L);
            if (failure instanceof TransportException transportFailure
                    && transportFailure.retryAfter() != null) {
                delay = Math.max(delay, durationToMillisSaturated(transportFailure.retryAfter()));
            }
            long remaining = durationToMillisSaturated(Duration.between(clock.instant(), deadline));
            return Math.max(1L, Math.min(delay, Math.max(1L, remaining)));
        }

        private void handle(LoginPollResult result) {
            if (result.phase() == LoginPhase.WAITING) {
                scheduleConfiguredInterval();
                return;
            }
            if (result.phase() == LoginPhase.SCANNED) {
                if (stateMachine.current() == ClientState.QR_WAITING) {
                    stateMachine.transitionTo(ClientState.QR_SCANNED, "用户已经扫描二维码");
                    LOGGER.info("登录二维码已扫码，等待用户确认");
                }
                scheduleConfiguredInterval();
                return;
            }
            if (result.phase() == LoginPhase.EXPIRED) {
                fail(new QrCodeExpiredException(), "登录二维码已过期", true);
                return;
            }
            succeed(result.session());
        }

        private void scheduleConfiguredInterval() {
            long base = config.loginPollInterval().toMillis();
            long spread = Math.max(1L, base / 5L);
            long minimum = Math.max(1L, base - spread);
            long maximum = base > Long.MAX_VALUE - spread ? Long.MAX_VALUE : base + spread;
            long delay = minimum >= maximum
                    ? minimum : ThreadLocalRandom.current().nextLong(minimum, maximum + 1L);
            scheduleNext(delay);
        }

        private void succeed(BotSession session) {
            boolean rejected;
            Throwable transitionFailure = null;
            synchronized (lifecycleLock) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                cancelScheduled();
                cancelInFlight();
                activeAttempt.compareAndSet(this, null);
                rejected = closed.get();
                if (!rejected) {
                    try {
                        // 轮询可能因网络抖动跳过 SCANNED 响应，确认成功时补齐合法状态路径。
                        if (stateMachine.current() == ClientState.QR_WAITING) {
                            stateMachine.transitionTo(
                                    ClientState.QR_SCANNED, "服务端已直接确认二维码登录");
                        }
                        stateMachine.transitionTo(ClientState.CONNECTED, "二维码登录确认成功");
                    } catch (Throwable failure) {
                        transitionFailure = failure;
                    }
                }
            }
            if (rejected) {
                completion.completeExceptionally(new ClientClosedException("登录控制器"));
            } else if (transitionFailure != null) {
                completion.completeExceptionally(transitionFailure);
            } else {
                LOGGER.info("二维码登录已经确认");
                completion.complete(session);
            }
        }

        private void fail(Throwable failure, String reason, boolean expired) {
            synchronized (lifecycleLock) {
                if (!finished.compareAndSet(false, true)) {
                    return;
                }
                cancelScheduled();
                cancelInFlight();
                activeAttempt.compareAndSet(this, null);
                ClientState current = stateMachine.current();
                if (!closed.get() && expired
                        && (current == ClientState.QR_WAITING || current == ClientState.QR_SCANNED)) {
                    stateMachine.transitionTo(ClientState.EXPIRED, reason);
                } else if (!closed.get()
                        && (current == ClientState.QR_WAITING || current == ClientState.QR_SCANNED)) {
                    stateMachine.transitionTo(ClientState.LOGIN_REQUIRED, reason);
                }
            }
            LOGGER.warn("二维码登录失败，reason={}，failureType={}",
                    reason, failure.getClass().getSimpleName());
            completion.completeExceptionally(failure);
        }

        private void cancelScheduled() {
            ScheduledFuture<?> task = scheduled.getAndSet(null);
            if (task != null) {
                task.cancel(false);
            }
        }

        private void cancelInFlight() {
            CompletableFuture<?> request = inFlight.getAndSet(null);
            if (request != null) {
                request.cancel(true);
            }
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static long durationToMillisSaturated(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return 0L;
        }
        try {
            return duration.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
