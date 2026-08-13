/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.session;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * 从旧 SDK 导出恢复字段时使用的一次性中间模型。
 *
 * <p>调用方应在迁移后删除明文导出文件。本对象包含敏感凭据，字符串表示始终脱敏。
 */
public record LegacyResumeData(
        String botToken,
        String userId,
        String botId,
        URI baseUri,
        String cursor,
        Map<String, String> contextTokens,
        Instant exportedAt) {

    @Override
    public String toString() {
        return "LegacyResumeData[botToken=***, userId=***, botId=***, baseUri="
                + baseUri + ", cursor=***, contextCount="
                + (contextTokens == null ? 0 : contextTokens.size()) + "]";
    }
}
