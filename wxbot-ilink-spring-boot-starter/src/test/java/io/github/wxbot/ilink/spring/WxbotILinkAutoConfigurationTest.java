/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.spring;

import io.github.wxbot.ilink.http.okhttp.ILinkOkHttpProtocol;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WxbotILinkAutoConfigurationTest {

    @Test
    void 应按配置创建有界Http基础设施() {
        try (AnnotationConfigApplicationContext context = context(Map.of(
                "wxbot.ilink.max-requests", "12",
                "wxbot.ilink.max-requests-per-host", "3",
                "wxbot.ilink.connect-timeout", "2s",
                "wxbot.ilink.request-timeout", "4s",
                "wxbot.ilink.long-poll-timeout", "9s"))) {
            OkHttpClient client = context.getBean(OkHttpClient.class);
            assertEquals(12, client.dispatcher().getMaxRequests());
            assertEquals(3, client.dispatcher().getMaxRequestsPerHost());
            assertEquals(2_000, client.connectTimeoutMillis());
            assertEquals(9_000, client.readTimeoutMillis());
            assertEquals(4_000, client.writeTimeoutMillis());
            assertEquals(9_000, client.callTimeoutMillis());
            assertNotNull(context.getBean(ILinkOkHttpProtocol.class));
        }
    }

    @Test
    void 关闭开关时不得自动装配() {
        try (AnnotationConfigApplicationContext context = context(
                Map.of("wxbot.ilink.enabled", "false"))) {
            assertFalse(context.containsBean("wxbotILinkOkHttpClient"));
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("测试配置", properties));
        context.register(WxbotILinkAutoConfiguration.class);
        context.refresh();
        return context;
    }
}
