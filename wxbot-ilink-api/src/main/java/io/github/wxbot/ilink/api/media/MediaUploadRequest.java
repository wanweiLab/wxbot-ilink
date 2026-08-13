/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

/**
 * 向 iLink 申请 CDN 上传参数所需的元数据。
 *
 * @param fileKey 本次上传的随机幂等键
 * @param mediaType 媒体类型
 * @param toUserId 接收方用户标识
 * @param digest 原文件摘要和长度
 * @param aesKeyHex AES-128 密钥十六进制值
 */
public record MediaUploadRequest(
        String fileKey,
        MediaType mediaType,
        String toUserId,
        MediaDigest digest,
        String aesKeyHex) {
}
