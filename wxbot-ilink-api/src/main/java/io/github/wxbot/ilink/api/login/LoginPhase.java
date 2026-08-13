/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.login;

/** 二维码登录协议阶段。 */
public enum LoginPhase {
    /** 等待用户扫码。 */
    WAITING,
    /** 用户已经扫码，等待确认。 */
    SCANNED,
    /** 登录已确认并返回有效会话。 */
    CONFIRMED,
    /** 二维码已经失效。 */
    EXPIRED
}
