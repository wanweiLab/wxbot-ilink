/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

import java.util.Objects;

/**
 * SDK 异常基类。
 *
 * <p>错误码用于业务判断，不能依赖异常消息进行分支处理。异常消息不得包含访问令牌、AES 密钥或完整 CDN 参数。
 */
public class ILinkException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    /**
     * 创建不包含底层原因的 SDK 异常。
     *
     * @param errorCode 稳定错误码
     * @param message 已脱敏的错误说明
     * @param retryable 当前操作在语义上是否允许重试
     */
    public ILinkException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = requireErrorCode(errorCode);
        this.retryable = retryable;
    }

    /**
     * 创建包含底层原因的 SDK 异常。
     *
     * @param errorCode 稳定错误码
     * @param message 已脱敏的错误说明
     * @param retryable 当前操作在语义上是否允许重试
     * @param cause 底层异常
     */
    public ILinkException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = requireErrorCode(errorCode);
        this.retryable = retryable;
    }

    /** @return 供程序判断的稳定错误码 */
    public String errorCode() {
        return errorCode;
    }

    /** @return 当前操作在语义上是否允许重试 */
    public boolean retryable() {
        return retryable;
    }

    private static String requireErrorCode(String errorCode) {
        Objects.requireNonNull(errorCode, "错误码不能为空");
        if (errorCode.isBlank()) {
            throw new IllegalArgumentException("错误码不能为空");
        }
        return errorCode;
    }
}
