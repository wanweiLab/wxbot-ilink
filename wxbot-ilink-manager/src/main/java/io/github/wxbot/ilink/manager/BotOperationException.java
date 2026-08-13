/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.manager;

/** Bot 管理操作失败，消息中不得携带令牌或二维码内容。 */
public final class BotOperationException extends RuntimeException {
    public BotOperationException(String message) {
        super(message);
    }

    public BotOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
