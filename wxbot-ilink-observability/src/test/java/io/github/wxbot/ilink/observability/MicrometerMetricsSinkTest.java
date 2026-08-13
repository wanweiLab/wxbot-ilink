/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerMetricsSinkTest {
    @Test
    void shouldWriteCountersTimersAndGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsSink sink = new MicrometerMetricsSink(registry);
        sink.increment("ilink.poll.failures", Map.of("kind", "network"));
        sink.recordDuration("ilink.poll.duration", Duration.ofMillis(25), Map.of());
        sink.gauge("ilink.dispatch.queue.size", 7, Map.of());
        sink.gauge("ilink.dispatch.queue.size", 3, Map.of());
        assertEquals(1.0, registry.get("ilink.poll.failures").counter().count());
        assertEquals(1, registry.get("ilink.poll.duration").timer().count());
        assertEquals(3.0, registry.get("ilink.dispatch.queue.size").gauge().value());
    }
}
