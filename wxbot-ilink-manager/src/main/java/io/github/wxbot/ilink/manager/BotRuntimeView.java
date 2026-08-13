/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.observability.ClientHealth;

/**
 * 管理接口可安全输出的 Bot 视图。
 *
 * @param registration 永久绑定信息
 * @param running 当前进程是否持有运行时对象
 * @param health 可选客户端健康快照
 */
public record BotRuntimeView(
        BotRegistration registration,
        boolean running,
        ClientHealth health) {
}
