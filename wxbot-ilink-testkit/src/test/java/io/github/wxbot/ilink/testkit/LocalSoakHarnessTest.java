/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.testkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSoakHarnessTest {

    @Test
    void 应统计固定时长负载结果() throws Exception {
        LocalSoakHarness.Result result = new LocalSoakHarness()
                .run(Duration.ofMillis(80), 100, message -> CompletableFuture.completedFuture(null))
                .toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertTrue(result.submitted() >= 2L);
        assertEquals(result.submitted(), result.succeeded());
        assertEquals(0L, result.failed());
    }
}
