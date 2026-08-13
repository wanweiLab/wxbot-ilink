/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.message;

/**
 * 出站消息使用的上下文引用。
 *
 * @param mode 上下文选择模式
 * @param value 显式令牌或来源消息标识；最新上下文模式下为空
 */
public record ContextReference(Mode mode, String value) {

    /** 上下文选择模式。 */
    public enum Mode {
        /** 使用会话缓存中的最新令牌。 */
        LATEST,
        /** 使用调用方提供的显式令牌。 */
        EXPLICIT,
        /** 使用指定入站消息携带的上下文令牌。 */
        FROM_MESSAGE
    }

    public ContextReference {
        if (mode == null) {
            throw new IllegalArgumentException("上下文模式不能为空");
        }
        if ((mode == Mode.EXPLICIT || mode == Mode.FROM_MESSAGE)
                && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("上下文引用值不能为空");
        }
        if (mode == Mode.LATEST) {
            value = null;
        }
    }

    /** @return 使用最新上下文的引用 */
    public static ContextReference latest() {
        return new ContextReference(Mode.LATEST, null);
    }

    /** @param token 显式上下文令牌；@return 显式引用 */
    public static ContextReference explicit(String token) {
        return new ContextReference(Mode.EXPLICIT, token);
    }

    /** @param messageId 来源入站消息标识；@return 来源消息引用 */
    public static ContextReference fromMessage(long messageId) {
        if (messageId <= 0L) {
            throw new IllegalArgumentException("来源消息标识必须大于零");
        }
        return new ContextReference(Mode.FROM_MESSAGE, Long.toString(messageId));
    }

    @Override
    public String toString() {
        return "ContextReference[mode=" + mode + ", value=***]";
    }
}
