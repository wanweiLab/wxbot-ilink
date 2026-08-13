/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 目标用户没有可用上下文令牌时抛出的异常。 */
public final class ContextMissingException extends ILinkException {
    public ContextMissingException(String userId) {
        super("ILINK-CONTEXT-001", "目标用户没有可用上下文：" + mask(userId), false);
    }

    private static String mask(String value) {
        return value == null || value.length() <= 4 ? "***" : value.substring(0, 2) + "***";
    }
}
