/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

import io.github.wxbot.ilink.api.state.ClientState;

/**
 * 客户端尝试执行未被状态机允许的状态转换时抛出的异常。
 */
public final class IllegalStateTransitionException extends ILinkException {

    private final ClientState from;
    private final ClientState to;

    /**
     * @param from 当前状态
     * @param to 被拒绝的目标状态
     */
    public IllegalStateTransitionException(ClientState from, ClientState to) {
        super("ILINK-STATE-001", "不允许从 " + from + " 转换到 " + to, false);
        this.from = from;
        this.to = to;
    }

    /** @return 当前状态 */
    public ClientState from() {
        return from;
    }

    /** @return 被拒绝的目标状态 */
    public ClientState to() {
        return to;
    }
}
