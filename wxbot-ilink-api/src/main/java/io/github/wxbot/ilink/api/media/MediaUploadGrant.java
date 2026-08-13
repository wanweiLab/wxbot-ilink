/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

/**
 * iLink 返回的 CDN 上传授权。
 *
 * <p>上传参数属于敏感短期凭据，字符串表示会主动脱敏。
 */
public record MediaUploadGrant(String encryptedQueryParameter) {
    @Override
    public String toString() {
        return "MediaUploadGrant[encryptedQueryParameter=***]";
    }
}
