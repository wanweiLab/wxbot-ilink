/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

/** @param fileKey 上传幂等键 @param media 可发送或下载的媒体引用 @param digest 原始内容摘要 */
public record UploadedMedia(String fileKey, CdnMedia media, MediaDigest digest) {
}
