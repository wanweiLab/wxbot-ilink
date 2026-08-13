/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.observability;

import java.util.Map;

/**
 * 可选链路追踪出口。
 *
 * <p>核心层不依赖具体追踪产品。属性必须保持低基数且经过脱敏，禁止传入 token、完整用户标识或消息正文。
 */
public interface TracingSink {

    /** 开始一个 SDK 操作跨度。 */
    Span start(String operation, Map<String, String> attributes);

    /** @return 不记录任何数据的默认实现 */
    static TracingSink noop() {
        return (operation, attributes) -> Span.NOOP;
    }

    /** 单个操作跨度。实现必须允许重复关闭。 */
    interface Span extends AutoCloseable {
        Span NOOP = new Span() {
            @Override
            public void error(Throwable failure) {
            }

            @Override
            public void close() {
            }
        };

        /** 记录已脱敏失败。 */
        void error(Throwable failure);

        /** 结束跨度。 */
        @Override
        void close();
    }
}
