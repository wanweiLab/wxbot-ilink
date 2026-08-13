/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 登录流程超过配置期限时抛出的异常。 */
public final class LoginTimeoutException extends ILinkException {
    public LoginTimeoutException() {
        super("ILINK-AUTH-001", "二维码登录超时", true);
    }
}
