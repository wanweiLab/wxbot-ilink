/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.exception;

/** 客户端或其子组件已经关闭，当前操作不能继续。 */
public final class ClientClosedException extends ILinkException {

    /** 创建稳定错误码的关闭异常。 */
    public ClientClosedException(String component) {
        super("ILINK-CLIENT-CLOSED", component + "已经关闭", false);
    }
}
