/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.login;

import io.github.wxbot.ilink.api.session.BotSession;

import java.util.concurrent.CompletionStage;

/**
 * 已启动的二维码登录任务。
 *
 * <p>{@link #cancel()} 是幂等操作。取消后 {@link #completion()} 以取消异常结束，且不会继续查询服务端。
 */
public interface LoginAttempt {

    /** @return 需要展示给用户的二维码 */
    QrCode qrCode();

    /** @return 登录完成阶段 */
    CompletionStage<BotSession> completion();

    /** @return 本次调用实际触发取消时返回 {@code true} */
    boolean cancel();
}
