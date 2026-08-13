/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.state;

import io.github.wxbot.ilink.api.exception.IllegalStateTransitionException;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateChangedEvent;
import io.github.wxbot.ilink.api.state.ClientStateListener;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 严格校验转换路径的客户端状态机。
 *
 * <p>状态写入和事件入队在同一把锁内完成，监听器在锁外按事件序号顺序调用。因此监听器可以安全读取状态，
 * 也不会占用状态机内部锁。监听器失败会交给失败处理器，不能中断后续监听器或状态事件。
 */
public final class ClientStateMachine {

    private static final Map<ClientState, Set<ClientState>> ALLOWED_TRANSITIONS = transitions();

    private final ReentrantLock transitionLock = new ReentrantLock();
    private final CopyOnWriteArrayList<ClientStateListener> listeners = new CopyOnWriteArrayList<>();
    private final Queue<ClientStateChangedEvent> pendingEvents = new ArrayDeque<>();
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private final Clock clock;
    private final Consumer<Throwable> listenerFailureHandler;

    private volatile ClientState current = ClientState.NEW;
    private long sequence;

    /**
     * 使用系统时钟创建状态机。监听器异常默认隔离且忽略，正式客户端应注入日志或指标处理器。
     */
    public ClientStateMachine() {
        this(Clock.systemUTC(), ignored -> { });
    }

    /**
     * 创建可测试的状态机。
     *
     * @param clock 生成事件时间的时钟
     * @param listenerFailureHandler 监听器异常处理器，该处理器本身不应抛出异常
     */
    public ClientStateMachine(Clock clock, Consumer<Throwable> listenerFailureHandler) {
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.listenerFailureHandler =
                Objects.requireNonNull(listenerFailureHandler, "监听器异常处理器不能为空");
    }

    /**
     * 获取当前状态。
     *
     * @return 最新已提交状态
     */
    public ClientState current() {
        return current;
    }

    /**
     * 注册状态监听器。重复注册同一实例只保留一份。
     *
     * @param listener 状态监听器
     */
    public void addListener(ClientStateListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "状态监听器不能为空"));
    }

    /**
     * 移除状态监听器。
     *
     * @param listener 状态监听器
     * @return 存在并成功移除时返回 {@code true}
     */
    public boolean removeListener(ClientStateListener listener) {
        return listeners.remove(listener);
    }

    /**
     * 将客户端转换到目标状态。
     *
     * <p>相同状态的重复请求视为幂等操作，不生成事件。成功返回时状态已经更新，但在并发情况下监听器回调可能仍在
     * 当前线程或另一个触发线程中排队执行。
     *
     * @param target 目标状态
     * @param reason 不包含敏感信息的转换原因
     * @return 发生实际转换时生成的事件；重复状态请求返回 {@code null}
     * @throws IllegalStateTransitionException 转换路径不合法时抛出
     */
    public ClientStateChangedEvent transitionTo(ClientState target, String reason) {
        Objects.requireNonNull(target, "目标状态不能为空");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("状态转换原因不能为空");
        }

        ClientStateChangedEvent event;
        transitionLock.lock();
        try {
            ClientState previous = current;
            if (previous == target) {
                return null;
            }
            if (!ALLOWED_TRANSITIONS.get(previous).contains(target)) {
                throw new IllegalStateTransitionException(previous, target);
            }

            event = new ClientStateChangedEvent(++sequence, previous, target, reason, clock.instant());
            current = target;
            pendingEvents.add(event);
        } finally {
            transitionLock.unlock();
        }

        drainEvents();
        return event;
    }

    private void drainEvents() {
        if (!dispatching.compareAndSet(false, true)) {
            return;
        }

        do {
            ClientStateChangedEvent event;
            while ((event = pollEvent()) != null) {
                notifyListeners(event);
            }
            dispatching.set(false);
            // 清空标记和新事件入队之间可能发生竞争，因此必须再次检查队列并竞争分发权。
        } while (hasPendingEvents() && dispatching.compareAndSet(false, true));
    }

    private ClientStateChangedEvent pollEvent() {
        transitionLock.lock();
        try {
            return pendingEvents.poll();
        } finally {
            transitionLock.unlock();
        }
    }

    private boolean hasPendingEvents() {
        transitionLock.lock();
        try {
            return !pendingEvents.isEmpty();
        } finally {
            transitionLock.unlock();
        }
    }

    private void notifyListeners(ClientStateChangedEvent event) {
        for (ClientStateListener listener : listeners) {
            try {
                listener.onStateChanged(event);
            } catch (Throwable failure) {
                reportListenerFailure(failure);
            }
        }
    }

    private void reportListenerFailure(Throwable failure) {
        try {
            listenerFailureHandler.accept(failure);
        } catch (Throwable ignored) {
            // 异常处理器是最后一道隔离边界，不能让它破坏状态机的事件分发。
        }
    }

    private static Map<ClientState, Set<ClientState>> transitions() {
        EnumMap<ClientState, Set<ClientState>> allowed = new EnumMap<>(ClientState.class);
        allowed.put(ClientState.NEW,
                EnumSet.of(ClientState.LOGIN_REQUIRED, ClientState.RESTORING, ClientState.CLOSING));
        allowed.put(ClientState.LOGIN_REQUIRED,
                EnumSet.of(ClientState.QR_WAITING, ClientState.RESTORING, ClientState.CLOSING));
        allowed.put(ClientState.QR_WAITING,
                EnumSet.of(ClientState.QR_SCANNED, ClientState.EXPIRED,
                        ClientState.LOGIN_REQUIRED, ClientState.CLOSING));
        allowed.put(ClientState.QR_SCANNED,
                EnumSet.of(ClientState.CONNECTED, ClientState.EXPIRED,
                        ClientState.LOGIN_REQUIRED, ClientState.CLOSING));
        allowed.put(ClientState.RESTORING,
                EnumSet.of(ClientState.CONNECTED, ClientState.LOGIN_REQUIRED,
                        ClientState.EXPIRED, ClientState.CLOSING));
        allowed.put(ClientState.CONNECTED,
                EnumSet.of(ClientState.DEGRADED, ClientState.RECONNECTING,
                        ClientState.EXPIRED, ClientState.CLOSING));
        allowed.put(ClientState.DEGRADED,
                EnumSet.of(ClientState.CONNECTED, ClientState.RECONNECTING,
                        ClientState.EXPIRED, ClientState.CLOSING));
        allowed.put(ClientState.RECONNECTING,
                EnumSet.of(ClientState.CONNECTED, ClientState.DEGRADED,
                        ClientState.EXPIRED, ClientState.LOGIN_REQUIRED, ClientState.CLOSING));
        allowed.put(ClientState.EXPIRED,
                EnumSet.of(ClientState.LOGIN_REQUIRED, ClientState.CLOSING));
        allowed.put(ClientState.CLOSING, EnumSet.of(ClientState.CLOSED));
        allowed.put(ClientState.CLOSED, EnumSet.noneOf(ClientState.class));
        return Map.copyOf(allowed);
    }
}
