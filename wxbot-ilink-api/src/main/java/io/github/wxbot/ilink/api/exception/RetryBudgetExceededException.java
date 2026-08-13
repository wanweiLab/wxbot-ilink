/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 当前时间窗口内的重试预算已经耗尽。 */
public final class RetryBudgetExceededException extends ILinkException {

    public RetryBudgetExceededException(Throwable cause) {
        super("ILINK-RESILIENCE-002", "当前时间窗口的重试预算已经耗尽", false, cause);
    }
}
