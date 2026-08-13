/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

/** iLink 上传接口支持的媒体类型。 */
public enum MediaType {
    IMAGE(1), VIDEO(2), FILE(3), VOICE(4);

    private final int protocolValue;

    MediaType(int protocolValue) {
        this.protocolValue = protocolValue;
    }

    /** @return iLink 协议整数值 */
    public int protocolValue() {
        return protocolValue;
    }
}
