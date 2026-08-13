/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.observability;

import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 将 SDK 低基数指标写入 Micrometer 注册表。 */
public final class MicrometerMetricsSink implements MetricsSink {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public MicrometerMetricsSink(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "Micrometer 注册表不能为空");
    }

    @Override
    public void increment(String name, Map<String, String> tags) {
        registry.counter(name, tags(tags)).increment();
    }

    @Override
    public void recordDuration(String name, Duration duration, Map<String, String> tags) {
        registry.timer(name, tags(tags)).record(duration);
    }

    @Override
    public void gauge(String name, long value, Map<String, String> tags) {
        String key = name + tags.toString();
        AtomicLong holder = gauges.computeIfAbsent(key, ignored -> {
            AtomicLong created = new AtomicLong();
            registry.gauge(name, tags(tags), created);
            return created;
        });
        holder.set(value);
    }

    private static Iterable<Tag> tags(Map<String, String> tags) {
        return tags.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> Tag.of(entry.getKey(), entry.getValue())).toList();
    }
}
