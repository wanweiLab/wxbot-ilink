/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.state;

/**
 * 客户端生命周期状态。
 *
 * <p>状态只能由核心状态机推进。调用方可以读取或监听状态，但不应自行推断并修改状态。
 */
public enum ClientState {
    /** 客户端刚创建，尚未选择登录或恢复流程。 */
    NEW,

    /** 当前没有可用会话，需要发起登录。 */
    LOGIN_REQUIRED,

    /** 正在等待用户扫描二维码。 */
    QR_WAITING,

    /** 二维码已扫描，正在等待登录确认。 */
    QR_SCANNED,

    /** 正在从持久化快照恢复客户端。 */
    RESTORING,

    /** 会话可用且消息拉取链路正常。 */
    CONNECTED,

    /** 会话仍可能可用，但连接质量已经下降。 */
    DEGRADED,

    /** 正在按照重连策略恢复连接。 */
    RECONNECTING,

    /** 登录会话已经过期，需要重新登录。 */
    EXPIRED,

    /** 客户端正在拒绝新任务并释放资源。 */
    CLOSING,

    /** 客户端已经关闭，不能再次使用。 */
    CLOSED
}
