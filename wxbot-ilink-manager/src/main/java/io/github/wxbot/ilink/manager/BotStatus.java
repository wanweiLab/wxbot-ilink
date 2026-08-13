/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

/** 管理后台保存的 Bot 生命周期状态。 */
public enum BotStatus {
    /** 已绑定业务用户，但还没有登录会话。 */
    LOGIN_REQUIRED,
    /** 正在等待用户扫描或确认二维码。 */
    LOGIN_PENDING,
    /** 客户端已经连接或正在持有运行租约。 */
    ONLINE,
    /** 客户端已主动停止，可从快照恢复。 */
    OFFLINE,
    /** 最近一次启动、登录或恢复失败。 */
    ERROR,
    /** 正在清理绑定和会话数据。 */
    DELETING
}
