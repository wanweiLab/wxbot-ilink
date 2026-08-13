/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

import java.util.Objects;

/**
 * 可放入消息载荷或用于下载的 CDN 媒体引用。
 *
 * <p>{@code aesKey} 与 {@code encryptedQueryParameter} 均为敏感信息，不会出现在字符串表示中。
 * 原始长度或摘要未知时可分别传 {@code -1} 和 {@code null}，下载仍会校验 AES 填充完整性。
 */
public record CdnMedia(
        String encryptedQueryParameter,
        String aesKey,
        int encryptionType,
        long rawLength,
        String md5Hex) {

    public CdnMedia {
        Objects.requireNonNull(encryptedQueryParameter, "CDN 加密参数不能为空");
        Objects.requireNonNull(aesKey, "AES 密钥不能为空");
        if (encryptionType <= 0 || rawLength < -1) {
            throw new IllegalArgumentException("媒体加密类型或长度不合法");
        }
    }

    @Override
    public String toString() {
        return "CdnMedia[encryptedQueryParameter=***, aesKey=***, encryptionType="
                + encryptionType + ", rawLength=" + rawLength + ", md5Hex=" + md5Hex + "]";
    }
}
