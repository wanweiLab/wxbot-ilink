/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.manager;

/** 用户已经绑定 Bot 或当前存在冲突操作。 */
public final class BotConflictException extends RuntimeException {
    public BotConflictException(String message) {
        super(message);
    }
}
