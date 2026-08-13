/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

/** 管理后台可持久化查询的二维码登录阶段。 */
public enum BotLoginPhase {
    /** 二维码已经生成，正在等待用户扫描。 */
    WAITING_SCAN,
    /** 用户已经扫描二维码，正在等待其在微信中确认。 */
    SCANNED,
    /** 用户已经在微信中确认，后台即将保存会话。 */
    CONFIRMED,
    /** 后台正在加密保存会话并建立永久业务绑定。 */
    BINDING,
    /** 加密会话已经保存，永久业务绑定完成。 */
    BOUND,
    /** 二维码或本次登录任务已经过期。 */
    EXPIRED,
    /** 本次登录因为网络、协议或持久化错误而失败。 */
    FAILED
}
