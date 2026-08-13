/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.LegacyResumeData;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LegacySnapshotMigratorTest {
    @Test
    void 应转换旧恢复数据且保持脱敏() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        LegacyResumeData legacy = new LegacyResumeData("secret-token", "u", "b",
                URI.create("https://example.test"), "cursor", Map.of("u1", "context"), now);
        var snapshot = LegacySnapshotMigrator.migrate(legacy, Clock.fixed(now, ZoneOffset.UTC));
        assertEquals("cursor", snapshot.cursor());
        assertEquals("context", snapshot.conversations().get("u1").contextToken());
        assertFalse(legacy.toString().contains("secret-token"));
    }
}
