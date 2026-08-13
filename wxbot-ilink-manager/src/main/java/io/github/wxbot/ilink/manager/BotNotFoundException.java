/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.manager;

/** 找不到指定业务用户的 Bot 绑定。 */
public final class BotNotFoundException extends RuntimeException {
    public BotNotFoundException(String message) {
        super(message);
    }
}
