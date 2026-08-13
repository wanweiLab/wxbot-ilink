/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 登录二维码被服务端判定为过期时抛出的异常。 */
public final class QrCodeExpiredException extends ILinkException {
    public QrCodeExpiredException() {
        super("ILINK-AUTH-002", "登录二维码已过期", true);
    }
}
