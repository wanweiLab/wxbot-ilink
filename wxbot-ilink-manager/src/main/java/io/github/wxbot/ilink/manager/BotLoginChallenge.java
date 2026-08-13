/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.session.BotSession;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 首次绑定时返回给用户的扫码挑战。
 *
 * <p>二维码内容只能在当前响应中展示，不进入注册表和日志。
 *
 * @param attemptId 登录尝试唯一标识，查询进度时必须原样携带
 * @param imageContent 二维码原始内容
 * @param expiresAt 失效时间
 * @param completion 登录完成阶段
 */
public record BotLoginChallenge(
        String attemptId,
        String imageContent,
        Instant expiresAt,
        CompletionStage<BotSession> completion) {

    public BotLoginChallenge {
        if (attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("登录尝试标识不能为空");
        }
        if (imageContent == null || imageContent.isBlank()) {
            throw new IllegalArgumentException("二维码内容不能为空");
        }
        Objects.requireNonNull(expiresAt, "二维码失效时间不能为空");
        Objects.requireNonNull(completion, "登录完成阶段不能为空");
    }

    @Override
    public String toString() {
        return "BotLoginChallenge[attemptId=" + attemptId
                + ", imageContent=***, expiresAt=" + expiresAt + "]";
    }
}
