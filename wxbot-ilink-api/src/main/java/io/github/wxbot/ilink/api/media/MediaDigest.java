/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

/**
 * 媒体摘要和长度信息。
 *
 * @param rawLength 原始字节数
 * @param encryptedLength PKCS7 填充后的密文字节数
 * @param md5Hex 原始内容 MD5 十六进制值
 */
public record MediaDigest(long rawLength, long encryptedLength, String md5Hex) {
}
