/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.exception.LoginTimeoutException;
import io.github.wxbot.ilink.api.exception.QrCodeExpiredException;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.store.jdbc.JdbcILinkStore;
import io.github.wxbot.ilink.manager.application.BotManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.wxbot.ilink.manager.domain.repository.BotRegistrationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按业务 {@code userId} 管理多个相互隔离的 Bot。
 *
 * <p>每个用户只创建一个客户端和一个独占协议对象，多个用户共享 JDBC 存储与 HTTP 连接池。首次扫码成功后
 * 自动加密保存快照，后续应调用 {@link #restore(String)} 恢复，不需要再次扫码。数据库租约负责多后台副本间的
 * 单活，进程内映射负责阻止同一用户的并发登录或恢复。
 */
public final class BotRuntimeManager implements BotManagementService, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotRuntimeManager.class);
    private static final Duration LOGIN_CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private final BotRegistrationRepository registry;
    private final JdbcILinkStore store;
    private final BotClientFactory clientFactory;
    private final Clock clock;
    private final ConcurrentHashMap<String, ManagedBotClient> runtimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 创建管理器；共享资源的生命周期仍由应用容器负责。 */
    public BotRuntimeManager(
            BotRegistrationRepository registry, JdbcILinkStore store, BotClientFactory clientFactory) {
        this(registry, store, clientFactory, Clock.systemUTC());
    }

    /** 创建使用指定时钟的管理器，主要供确定性测试和统一部署时钟使用。 */
    public BotRuntimeManager(
            BotRegistrationRepository registry, JdbcILinkStore store, BotClientFactory clientFactory,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry, "Bot 注册表不能为空");
        this.store = Objects.requireNonNull(store, "SDK JDBC 存储不能为空");
        this.clientFactory = Objects.requireNonNull(clientFactory, "Bot 客户端工厂不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /**
     * 为业务用户创建唯一绑定。
     *
     * @param userId 业务用户唯一标识
     * @param displayName 展示名称
     * @return 新绑定
     */
    public CompletionStage<BotRegistration> bind(String userId, String displayName) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        String name = required(displayName, "展示名称", 128);
        LOGGER.info("开始创建 Bot 绑定，userId={}", normalized);
        return registry.create(normalized, clientKey(normalized), name);
    }

    /** @return 全部 Bot 的安全运行视图 */
    public CompletionStage<List<BotRuntimeView>> list() {
        ensureOpen();
        return registry.list().thenApply(values -> values.stream().map(this::view).toList());
    }

    /** 查询一个用户的 Bot。 */
    public CompletionStage<BotRuntimeView> get(String userId) {
        ensureOpen();
        return requireRegistration(normalizeUserId(userId)).thenApply(this::view);
    }

    /**
     * 发起首次扫码或会话失效后的重新登录。
     *
     * <p>已经存在运行时的用户不能重复发起，以免生成多个二维码和竞争会话。
     */
    public CompletionStage<BotLoginChallenge> login(String userId) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        LOGGER.info("开始二维码登录，userId={}", normalized);
        return requireRegistration(normalized).thenCompose(registration ->
                reclaimExpiredLogin(registration).thenCompose(reclaimed -> {
                    if (registration.status() == BotStatus.LOGIN_PENDING && !reclaimed) {
                        return CompletableFuture.failedFuture(new BotConflictException(
                                "该用户已有正在进行的二维码登录"));
                    }
                    String attemptId = UUID.randomUUID().toString();
                    Instant fallbackExpiry = clock.instant().plus(LOGIN_CLAIM_TIMEOUT);
                    return registry.beginLogin(normalized,
                                    Set.of(BotStatus.LOGIN_REQUIRED, BotStatus.ERROR),
                                    attemptId, fallbackExpiry)
                            .thenCompose(claimed -> claimed
                                    ? startLogin(normalized, registration, attemptId)
                                    : CompletableFuture.failedFuture(new BotConflictException(
                                            "该用户已有可恢复会话，或另一个后台实例正在操作")));
                }));
    }

    private CompletionStage<Boolean> reclaimExpiredLogin(BotRegistration registration) {
        if (registration.status() != BotStatus.LOGIN_PENDING) {
            return CompletableFuture.completedFuture(false);
        }
        return registry.findCurrentLoginAttempt(registration.userId()).thenCompose(optional -> {
            if (optional.isEmpty() || !canExpireByQrDeadline(optional.get().phase())
                    || optional.get().expiresAt().isAfter(clock.instant())) {
                return CompletableFuture.completedFuture(false);
            }
            BotLoginAttempt attempt = optional.get();
            return registry.failLogin(registration.userId(), attempt.attemptId(),
                    BotLoginPhase.EXPIRED, BotStatus.LOGIN_REQUIRED, "二维码已过期");
        });
    }

    private CompletionStage<BotLoginChallenge> startLogin(
            String normalized, BotRegistration registration, String attemptId) {
        ManagedBotClient runtime;
        AtomicBoolean bound = new AtomicBoolean();
        AtomicBoolean expired = new AtomicBoolean();
        synchronized (lock(normalized)) {
            ManagedBotClient staleRuntime = runtimes.remove(normalized);
            if (staleRuntime != null) {
                try {
                    staleRuntime.close();
                } catch (RuntimeException failure) {
                    return failClaimedLogin(normalized, attemptId, failure,
                            "清理旧 Bot 运行时失败");
                }
            }
            try {
                runtime = clientFactory.create(normalized, registration.clientKey());
            } catch (RuntimeException failure) {
                return failClaimedLogin(normalized, attemptId, failure, "创建 Bot 运行时失败");
            }
            runtimes.put(normalized, runtime);
            observeLoginStates(normalized, attemptId, registration.clientKey(), runtime, bound, expired);
        }
        CompletionStage<LoginAttempt> login;
        try {
            login = runtime.client().login();
        } catch (Throwable failure) {
            failLoginAndRemove(normalized, attemptId, runtime,
                    unwrap(failure), "生成登录二维码失败");
            return CompletableFuture.failedFuture(failure);
        }
        return login
                .thenCompose(attempt -> registry.updateLoginChallenge(
                                normalized, attemptId, attempt.qrCode().expiresAt())
                        .thenCompose(updated -> updated
                                ? CompletableFuture.completedFuture(challenge(
                                        normalized, attemptId, runtime, attempt, bound, expired))
                                : CompletableFuture.failedFuture(new BotConflictException(
                                        "二维码登录尝试已经失效"))))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        failLoginAndRemove(normalized, attemptId, runtime,
                                unwrap(failure), "生成登录二维码失败");
                    }
                });
    }

    private BotLoginChallenge challenge(
            String userId, String attemptId, ManagedBotClient runtime,
            LoginAttempt attempt, AtomicBoolean bound, AtomicBoolean expired) {
        CompletionStage<BotSession> completion = attempt.completion()
                .thenCompose(session -> requirePhaseUpdate(userId, attemptId,
                        BotLoginPhase.CONFIRMED, "用户已经在微信中确认").thenApply(ignored -> session))
                .thenCompose(session -> requirePhaseUpdate(userId, attemptId,
                        BotLoginPhase.BINDING, "正在加密保存微信身份与会话").thenApply(ignored -> session))
                .thenCompose(session -> runtime.client().saveSnapshot().thenCompose(snapshot -> {
                    if (snapshot.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new BotOperationException("登录成功但会话快照保存失败"));
                    }
                    return registry.completeLogin(userId, attemptId).thenCompose(completed -> {
                        if (!completed) {
                            return CompletableFuture.failedFuture(
                                    new BotConflictException("二维码登录尝试已经失效"));
                        }
                        bound.set(true);
                        if (!expired.get()) {
                            return CompletableFuture.completedFuture(session);
                        }
                        return invalidateBoundLogin(userId, attemptId)
                                .thenCompose(ignored -> CompletableFuture.failedFuture(
                                        new BotOperationException("微信会话在绑定完成前已经失效")));
                    });
                }));
        completion.whenComplete((ignored, failure) -> {
            if (failure != null) {
                failLoginAndRemove(userId, attemptId, runtime,
                        unwrap(failure), "扫码登录未完成");
            }
        });
        return new BotLoginChallenge(
                attemptId, attempt.qrCode().imageContent(), attempt.qrCode().expiresAt(), completion);
    }

    private CompletionStage<Void> requirePhaseUpdate(
            String userId, String attemptId, BotLoginPhase phase, String message) {
        return registry.updateLoginPhase(userId, attemptId, phase, message)
                .thenCompose(updated -> updated
                        ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(
                                new BotConflictException("二维码登录尝试已经失效")));
    }

    /** 查询指定二维码尝试；绑定完成后从加密快照补充微信身份。 */
    public CompletionStage<BotLoginStatusView> getLoginStatus(
            String userId, String attemptId) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        String normalizedAttemptId = required(attemptId, "登录尝试标识", 64);
        return requireRegistration(normalized).thenCompose(registration ->
                requireLoginAttempt(normalized, normalizedAttemptId).thenCompose(attempt -> {
                    if (canExpireByQrDeadline(attempt.phase())
                            && !attempt.expiresAt().isAfter(clock.instant())) {
                        return registry.failLogin(normalized, normalizedAttemptId,
                                        BotLoginPhase.EXPIRED, BotStatus.LOGIN_REQUIRED,
                                        "二维码已过期")
                                .thenCompose(ignored -> requireRegistration(normalized)
                                        .thenCompose(latestRegistration -> requireLoginAttempt(
                                                        normalized, normalizedAttemptId)
                                                .thenCompose(latestAttempt -> statusView(
                                                        latestRegistration, latestAttempt))));
                    }
                    return statusView(registration, attempt);
                }));
    }

    private CompletionStage<BotLoginAttempt> requireLoginAttempt(
            String userId, String attemptId) {
        return registry.findLoginAttempt(userId, attemptId).thenCompose(optional -> optional
                .<CompletionStage<BotLoginAttempt>>map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new BotNotFoundException("二维码登录尝试不存在"))));
    }

    private CompletionStage<BotLoginStatusView> statusView(
            BotRegistration registration, BotLoginAttempt attempt) {
        if (attempt.phase() != BotLoginPhase.BOUND) {
            return CompletableFuture.completedFuture(toStatusView(attempt, null));
        }
        return store.load(registration.clientKey()).thenCompose(snapshot -> snapshot
                .<CompletionStage<BotLoginStatusView>>map(value ->
                        CompletableFuture.completedFuture(toStatusView(attempt, value.session())))
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new BotOperationException("绑定已完成但加密会话快照不存在"))));
    }

    private static BotLoginStatusView toStatusView(
            BotLoginAttempt attempt, BotSession session) {
        return new BotLoginStatusView(
                attempt.attemptId(), attempt.phase(), attempt.message(),
                attempt.registrationStatus(), session == null ? null : session.userId(),
                session == null ? null : session.botId(), attempt.expiresAt(),
                attempt.createdAt(), attempt.updatedAt(), attempt.version());
    }

    /** 二维码期限只约束尚未在微信确认的阶段，确认后的加密落库不能被该期限打断。 */
    private static boolean canExpireByQrDeadline(BotLoginPhase phase) {
        return phase == BotLoginPhase.WAITING_SCAN || phase == BotLoginPhase.SCANNED;
    }

    /**
     * 从首次扫码保存的加密快照恢复 Bot。
     *
     * @return 找到有效快照时为 {@code true}；否则用户需要重新扫码
     */
    public CompletionStage<Boolean> restore(String userId) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        return requireRegistration(normalized).thenCompose(registration -> {
            if (registration.status() == BotStatus.LOGIN_PENDING) {
                return CompletableFuture.failedFuture(
                        new BotConflictException("该用户正在进行二维码登录，不能同时恢复会话"));
            }
            ManagedBotClient runtime;
            synchronized (lock(normalized)) {
                if (runtimes.containsKey(normalized)) {
                    return CompletableFuture.completedFuture(true);
                }
                runtime = clientFactory.create(normalized, registration.clientKey());
                runtimes.put(normalized, runtime);
                observeSessionExpiry(normalized, runtime);
            }
            return runtime.client().restore().thenCompose(restored -> {
                if (restored) {
                    LOGGER.info("Bot 会话恢复成功，userId={}", normalized);
                    return registry.updateStatus(normalized, BotStatus.ONLINE, null)
                            .thenApply(ignored -> true);
                }
                removeAndClose(normalized, runtime);
                LOGGER.warn("Bot 会话不可恢复，需要重新扫码，userId={}", normalized);
                return registry.updateStatus(normalized, BotStatus.LOGIN_REQUIRED, null)
                        .thenApply(ignored -> false);
            }).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.error("Bot 会话恢复异常，userId={}", normalized, unwrap(failure));
                    removeAndClose(normalized, runtime);
                    registry.updateStatus(normalized, BotStatus.ERROR, "恢复加密会话失败")
                            .exceptionally(updateFailure -> null);
                }
            });
        });
    }

    /** 向指定用户的在线 Bot 发送消息。 */
    public CompletionStage<SendReceipt> send(String userId, SendMessageRequest request) {
        ensureOpen();
        ManagedBotClient runtime = runtimes.get(normalizeUserId(userId));
        if (runtime == null) {
            return CompletableFuture.failedFuture(new BotConflictException("该用户的 Bot 当前未运行"));
        }
        return runtime.client().send(Objects.requireNonNull(request, "发送请求不能为空"));
    }

    /**
     * 向当前业务用户扫码绑定的微信身份发送测试文本。
     *
     * <p>目标微信 userId 只从 AES-GCM 加密快照读取，管理接口不能指定任意接收者。注册状态和本实例运行时
     * 必须同时在线，避免把数据库中的历史状态误当成可发送状态。
     *
     * @param userId 业务用户唯一标识
     * @param text 测试文本内容
     * @return 发送回执
     */
    public CompletionStage<SendReceipt> sendTestMessage(String userId, String text) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        String normalizedText = required(text, "测试文本内容", 2_000);
        return requireRegistration(normalized).thenCompose(registration -> {
            if (registration.status() != BotStatus.ONLINE) {
                return CompletableFuture.failedFuture(
                        new BotConflictException("该用户尚未完成绑定或 Bot 不在线"));
            }
            ManagedBotClient runtime = runtimes.get(normalized);
            if (runtime == null) {
                return CompletableFuture.failedFuture(
                        new BotConflictException("该用户的 Bot 不在当前后台实例运行"));
            }
            return store.load(registration.clientKey()).thenCompose(optional -> optional
                    .<CompletionStage<SendReceipt>>map(snapshot -> {
                        if (runtimes.get(normalized) != runtime) {
                            return CompletableFuture.failedFuture(
                                    new BotConflictException("该用户的 Bot 运行状态已经变化"));
                        }
                        SendMessageRequest request = SendMessageRequest.text(
                                UUID.randomUUID().toString(),
                                snapshot.session().userId(), normalizedText);
                        LOGGER.info("提交绑定身份测试消息，userId={}，clientId={}，textLength={}",
                                normalized, request.clientId(), normalizedText.length());
                        return runtime.client().send(request).whenComplete((receipt, failure) -> {
                            if (failure == null) {
                                LOGGER.info("绑定身份测试消息发送成功，userId={}，clientId={}",
                                        normalized, request.clientId());
                            } else {
                                LOGGER.error("绑定身份测试消息发送失败，userId={}，clientId={}",
                                        normalized, request.clientId(), unwrap(failure));
                            }
                        });
                    })
                    .orElseGet(() -> CompletableFuture.failedFuture(
                            new BotConflictException("该用户尚未完成微信身份绑定"))));
        });
    }

    /** 保存会话并停止一个 Bot；绑定仍然保留。 */
    public CompletionStage<Void> stop(String userId) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        LOGGER.info("开始停止 Bot，userId={}", normalized);
        return requireRegistration(normalized).thenCompose(ignored -> stopRuntime(normalized))
                .thenCompose(ignored -> registry.updateStatus(normalized, BotStatus.OFFLINE, null))
                .thenApply(ignored -> {
                    LOGGER.info("Bot 已停止，userId={}", normalized);
                    return null;
                });
    }

    private CompletionStage<Void> stopRuntime(String userId) {
        ManagedBotClient runtime;
        synchronized (lock(userId)) {
            runtime = runtimes.remove(userId);
        }
        if (runtime == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<?> saved;
        try {
            saved = runtime.client().saveSnapshot().exceptionally(ignored -> Optional.empty());
        } catch (RuntimeException ignored) {
            saved = CompletableFuture.completedFuture(Optional.empty());
        }
        return saved.handle((value, failure) -> {
            runtime.close();
            return null;
        });
    }

    /** 停止运行时、清除加密会话和消息数据，并解除用户唯一绑定。 */
    public CompletionStage<Void> unbind(String userId) {
        ensureOpen();
        String normalized = normalizeUserId(userId);
        LOGGER.info("开始解除 Bot 绑定，userId={}", normalized);
        return requireRegistration(normalized)
                .thenCompose(registration -> registry.updateStatus(
                                normalized, BotStatus.DELETING, null)
                        .thenCompose(ignored -> stopRuntime(normalized))
                        .thenCompose(ignored -> store.purgeClient(registration.clientKey()))
                        .thenCompose(ignored -> registry.delete(normalized)))
                .thenRun(() -> LOGGER.info("Bot 绑定及持久化数据已清理，userId={}", normalized));
    }

    /** 按顺序恢复全部非删除状态 Bot，避免启动瞬间形成数据库和网络请求风暴。 */
    public CompletionStage<List<String>> restoreAll() {
        ensureOpen();
        return registry.list().thenCompose(registrations -> {
            CompletionStage<List<String>> chain =
                    CompletableFuture.completedFuture(new ArrayList<>());
            for (BotRegistration registration : registrations) {
                if (registration.status() == BotStatus.DELETING
                        || registration.status() == BotStatus.LOGIN_REQUIRED
                        || registration.status() == BotStatus.LOGIN_PENDING) {
                    continue;
                }
                chain = chain.thenCompose(restoredUsers -> restore(registration.userId())
                        .handle((restored, failure) -> {
                            if (failure == null && Boolean.TRUE.equals(restored)) {
                                restoredUsers.add(registration.userId());
                            }
                            return restoredUsers;
                        }));
            }
            return chain.thenApply(List::copyOf);
        });
    }

    private BotRuntimeView view(BotRegistration registration) {
        ManagedBotClient runtime = runtimes.get(registration.userId());
        return new BotRuntimeView(
                registration, runtime != null,
                runtime == null ? null : runtime.client().health());
    }

    private CompletionStage<BotRegistration> requireRegistration(String userId) {
        return registry.find(userId).thenCompose(optional -> optional
                .<CompletionStage<BotRegistration>>map(CompletableFuture::completedFuture)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new BotNotFoundException("该用户尚未绑定 Bot"))));
    }

    private <T> CompletionStage<T> failClaimedLogin(
            String userId, String attemptId, Throwable failure, String safeMessage) {
        Throwable cause = failure == null ? new BotConflictException(safeMessage) : failure;
        return registry.failLogin(userId, attemptId, BotLoginPhase.FAILED,
                        BotStatus.ERROR, safeMessage)
                .handle((ignored, updateFailure) -> {
                    if (updateFailure != null) {
                        cause.addSuppressed(unwrap(updateFailure));
                    }
                    return CompletableFuture.<T>failedFuture(cause);
                }).thenCompose(stage -> stage);
    }

    private void failLoginAndRemove(
            String userId, String attemptId, ManagedBotClient runtime,
            Throwable failure, String safeMessage) {
        try {
            removeAndClose(userId, runtime);
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        LOGGER.error("二维码登录失败，userId={}，attemptId={}，reason={}",
                userId, attemptId, safeMessage, failure);
        BotLoginPhase phase = failure instanceof QrCodeExpiredException
                || failure instanceof LoginTimeoutException
                ? BotLoginPhase.EXPIRED : BotLoginPhase.FAILED;
        BotStatus status = phase == BotLoginPhase.EXPIRED
                ? BotStatus.LOGIN_REQUIRED : BotStatus.ERROR;
        registry.failLogin(userId, attemptId, phase, status,
                        phase == BotLoginPhase.EXPIRED ? "二维码已过期" : safeMessage)
                .exceptionally(ignored -> false);
    }

    private void observeLoginStates(
            String userId, String attemptId, String clientKey, ManagedBotClient runtime,
            AtomicBoolean bound, AtomicBoolean expired) {
        runtime.client().addStateListener(event -> {
            if (event.current() == ClientState.QR_SCANNED) {
                registry.updateLoginPhase(userId, attemptId, BotLoginPhase.SCANNED,
                                "用户已扫码，请在微信中确认")
                        .exceptionally(ignored -> false);
                return;
            }
            if (event.current() != ClientState.EXPIRED
                    || !runtimes.remove(userId, runtime)) {
                return;
            }
            expired.set(true);
            if (bound.get()) {
                invalidateBoundLogin(userId, attemptId)
                        .exceptionally(ignored -> false);
            } else {
                registry.failLogin(userId, attemptId, BotLoginPhase.EXPIRED,
                                BotStatus.LOGIN_REQUIRED, "二维码已过期")
                        .thenCompose(finished -> finished
                                ? CompletableFuture.completedFuture(true)
                                : invalidateBoundLogin(userId, attemptId))
                        .exceptionally(ignored -> false);
            }
            CompletableFuture.runAsync(runtime::close);
        });
    }

    private void observeSessionExpiry(String userId, ManagedBotClient runtime) {
        runtime.client().addStateListener(event -> {
            if (event.current() != ClientState.EXPIRED || !runtimes.remove(userId, runtime)) {
                return;
            }
            LOGGER.warn("Bot 会话已失效并移除运行时，userId={}", userId);
            registry.findCurrentLoginAttempt(userId)
                    .thenCompose(attempt -> attempt
                            .<CompletionStage<Boolean>>map(value -> invalidateBoundLogin(
                                    userId, value.attemptId()))
                            .orElseGet(() -> registry.compareAndSetStatus(
                                    userId, Set.of(BotStatus.ONLINE),
                                    BotStatus.LOGIN_REQUIRED)))
                    .thenCompose(changed -> changed
                            ? CompletableFuture.completedFuture(true)
                            : registry.compareAndSetStatus(
                                    userId, Set.of(BotStatus.ONLINE),
                                    BotStatus.LOGIN_REQUIRED))
                    .exceptionally(ignored -> false);
            CompletableFuture.runAsync(runtime::close);
        });
    }

    /** 将已经提交但随即失效的绑定原子降级，避免留下 ONLINE 与关闭运行时并存的假在线状态。 */
    private CompletionStage<Boolean> invalidateBoundLogin(String userId, String attemptId) {
        return registry.failLogin(userId, attemptId, BotLoginPhase.FAILED,
                        BotStatus.LOGIN_REQUIRED, "微信会话已经失效，请重新扫码")
                .thenCompose(changed -> changed
                        ? CompletableFuture.completedFuture(true)
                        : registry.compareAndSetStatus(
                                userId, Set.of(BotStatus.ONLINE),
                                BotStatus.LOGIN_REQUIRED));
    }

    private void removeAndClose(String userId, ManagedBotClient runtime) {
        if (runtimes.remove(userId, runtime)) {
            runtime.close();
        }
    }

    private Object lock(String userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private static String clientKey(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.getBytes(StandardCharsets.UTF_8));
            return "user:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }

    private static String normalizeUserId(String value) {
        return required(value, "用户唯一标识", 191);
    }

    private static String required(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + "长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new BotOperationException("Bot 管理器已经关闭");
        }
    }

    /** 关闭当前进程中的全部 Bot，不删除绑定或共享数据库资源。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ManagedBotClient> clients = new ArrayList<>(runtimes.values());
        runtimes.clear();
        clients.forEach(ManagedBotClient::close);
        LOGGER.info("Bot 运行时管理器已关闭，closedClients={}", clients.size());
    }
}
