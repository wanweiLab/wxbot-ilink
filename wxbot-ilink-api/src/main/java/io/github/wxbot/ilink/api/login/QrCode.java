/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.login;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次登录二维码响应。
 *
 * @param token 查询二维码状态使用的不透明令牌
 * @param imageContent 可交给二维码渲染器的内容
 * @param expiresAt 二维码失效时间
 */
public record QrCode(String token, String imageContent, Instant expiresAt) {

    public QrCode {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("二维码令牌不能为空");
        }
        if (imageContent == null || imageContent.isBlank()) {
            throw new IllegalArgumentException("二维码内容不能为空");
        }
        Objects.requireNonNull(expiresAt, "二维码失效时间不能为空");
    }

    @Override
    public String toString() {
        return "QrCode[token=***, imageContent=***, expiresAt=" + expiresAt + "]";
    }
}
