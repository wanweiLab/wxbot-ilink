/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.state;

/**
 * 客户端状态监听器。
 *
 * <p>监听器由触发状态变更的线程调用，但不会在状态机内部锁中运行。实现必须尽快返回；需要执行阻塞任务时，
 * 应转交给业务自己的执行器。一个监听器抛出的异常不会阻止其他监听器收到事件。
 */
@FunctionalInterface
public interface ClientStateListener {

    /**
     * 处理状态变更事件。
     *
     * @param event 已完成的状态变更
     */
    void onStateChanged(ClientStateChangedEvent event);
}
