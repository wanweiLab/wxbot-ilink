/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBudgetTest {

    @Test
    void 应限制窗口内重试并在窗口滑动后恢复() {
        MutableClock clock = new MutableClock();
        RetryBudget budget = new RetryBudget(Duration.ofSeconds(10), 0.2D, 1, 3, clock);
        for (int index = 0; index < 10; index++) {
            budget.recordRequest();
        }

        assertTrue(budget.tryAcquireRetry());
        assertTrue(budget.tryAcquireRetry());
        assertFalse(budget.tryAcquireRetry());

        clock.advance(Duration.ofSeconds(11));
        assertTrue(budget.tryAcquireRetry());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
