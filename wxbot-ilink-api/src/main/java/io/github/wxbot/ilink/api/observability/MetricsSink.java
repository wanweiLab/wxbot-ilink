/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.observability;

import java.time.Duration;
import java.util.Map;

/**
 * 核心运行指标出口。
 *
 * <p>核心模块只依赖该低成本接口。实现不得在调用线程执行网络 IO，也不得使用 userId、messageId 或
 * clientId 作为标签。Micrometer 等监控系统应在独立适配模块中实现本接口。
 */
public interface MetricsSink {

    /** 增加一个计数器。 */
    void increment(String name, Map<String, String> tags);

    /** 记录一次耗时。 */
    void recordDuration(String name, Duration duration, Map<String, String> tags);

    /** 更新一个瞬时值。 */
    void gauge(String name, long value, Map<String, String> tags);

    /** @return 不执行任何操作的默认实现 */
    static MetricsSink noop() {
        return NoopHolder.INSTANCE;
    }

    /** 延迟创建无状态空实现，避免每次调用分配对象。 */
    final class NoopHolder {
        private static final MetricsSink INSTANCE = new MetricsSink() {
            @Override
            public void increment(String name, Map<String, String> tags) {
            }

            @Override
            public void recordDuration(String name, Duration duration, Map<String, String> tags) {
            }

            @Override
            public void gauge(String name, long value, Map<String, String> tags) {
            }
        };

        private NoopHolder() {
        }
    }
}
