/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 媒体读取、加解密、上传、下载或完整性校验失败。 */
public final class MediaException extends ILinkException {
    public MediaException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(errorCode, message, retryable, cause);
    }
}
