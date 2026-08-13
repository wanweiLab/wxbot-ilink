/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 当前 Bot 的故障熔断器处于打开状态，调用被快速拒绝。 */
public final class CircuitOpenException extends ILinkException {

    public CircuitOpenException() {
        super("ILINK-RESILIENCE-001", "服务暂时不可用，熔断器已打开", true);
    }
}
