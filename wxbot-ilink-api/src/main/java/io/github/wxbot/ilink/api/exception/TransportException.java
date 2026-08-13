/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

import java.time.Duration;

/**
 * 网络、HTTP 或协议传输失败。
 *
 * <p>可选元数据用于稳定重试和问题定位，不包含鉴权令牌或请求正文。未知数值使用 {@code null}，尝试次数未知时
 * 使用 0。
 */
public class TransportException extends ILinkException {

    private final Integer httpStatus;
    private final Integer protocolCode;
    private final String requestId;
    private final String endpoint;
    private final int attempt;
    private final Duration retryAfter;

    public TransportException(String errorCode, String message, boolean retryable, Throwable cause) {
        this(errorCode, message, retryable, cause, null, null, null, null, 0, null);
    }

    /** 创建包含脱敏传输元数据的异常。 */
    public TransportException(
            String errorCode,
            String message,
            boolean retryable,
            Throwable cause,
            Integer httpStatus,
            Integer protocolCode,
            String requestId,
            String endpoint,
            int attempt,
            Duration retryAfter) {
        super(errorCode, message, retryable, cause);
        if (attempt < 0) {
            throw new IllegalArgumentException("尝试次数不能为负数");
        }
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("服务端重试等待时间不能为负数");
        }
        this.httpStatus = httpStatus;
        this.protocolCode = protocolCode;
        this.requestId = blankToNull(requestId);
        this.endpoint = blankToNull(endpoint);
        this.attempt = attempt;
        this.retryAfter = retryAfter;
    }

    /** @return HTTP 状态码，未知时为空 */
    public Integer httpStatus() {
        return httpStatus;
    }

    /** @return 协议错误码，未知时为空 */
    public Integer protocolCode() {
        return protocolCode;
    }

    /** @return 服务端请求标识，未知时为空 */
    public String requestId() {
        return requestId;
    }

    /** @return 不含查询参数的请求路径，未知时为空 */
    public String endpoint() {
        return endpoint;
    }

    /** @return 当前请求尝试次数，未知时为 0 */
    public int attempt() {
        return attempt;
    }

    /** @return 服务端建议的重试等待时间，未知时为空 */
    public Duration retryAfter() {
        return retryAfter;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
