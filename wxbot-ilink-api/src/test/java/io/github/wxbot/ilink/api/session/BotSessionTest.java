/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bot 会话的参数校验与敏感字段保护测试。 */
class BotSessionTest {

    @Test
    void 字符串表示不会泄露令牌和完整微信身份() {
        BotSession session = new BotSession(
                "secret-token", "wechat-user-123", "wechat-bot-456",
                URI.create("https://example.test"));

        String text = session.toString();

        assertFalse(text.contains("secret-token"));
        assertFalse(text.contains("wechat-user-123"));
        assertFalse(text.contains("wechat-bot-456"));
        assertTrue(text.contains("we***23"));
        assertTrue(text.contains("we***56"));
    }
}
