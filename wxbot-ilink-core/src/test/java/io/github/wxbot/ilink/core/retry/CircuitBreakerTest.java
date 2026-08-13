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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void 达到阈值后应打开并仅允许一个半开探测() {
        MutableClock clock = new MutableClock();
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(10), clock);

        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquirePermission());

        clock.advance(Duration.ofSeconds(10));
        assertTrue(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertTrue(breaker.tryAcquirePermission());
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
