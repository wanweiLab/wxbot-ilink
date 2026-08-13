/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** SDK 配置缺失、冲突或超出允许边界。 */
public final class ConfigurationException extends ILinkException {

    /** 创建不包含敏感配置值的配置异常。 */
    public ConfigurationException(String message) {
        super("ILINK-CONFIG-INVALID", message, false);
    }

    /** 创建包含底层原因的配置异常。 */
    public ConfigurationException(String message, Throwable cause) {
        super("ILINK-CONFIG-INVALID", message, false, cause);
    }
}
