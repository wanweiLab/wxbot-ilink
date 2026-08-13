/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 服务端明确判定当前 Bot 会话已经失效。 */
public final class SessionExpiredException extends ILinkException {
    public SessionExpiredException() {
        super("ILINK-AUTH-003", "Bot 会话已经失效", false);
    }
}
