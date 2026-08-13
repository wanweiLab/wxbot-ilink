/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.config;

import io.github.wxbot.ilink.api.exception.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ILinkClientConfigTest {

    @Test
    void 应创建带安全默认值的配置() {
        ILinkClientConfig config = ILinkClientConfig.builder().build();

        assertEquals(Duration.ofSeconds(10), config.connectTimeout());
        assertEquals(Duration.ofSeconds(35), config.longPollTimeout());
        assertEquals(Duration.ofSeconds(1), config.loginPollInterval());
        assertEquals(Duration.ofSeconds(30), config.leaseTtl());
        assertEquals(Duration.ofSeconds(10), config.leaseRenewInterval());
        assertEquals(1024, config.dispatchQueueCapacity());
        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofSeconds(30), config.messageProcessingTimeout());
        assertEquals(8, config.maxDeliveryAttempts());
        assertEquals(Duration.ofHours(24), config.contextTtl());
    }

    @Test
    void 应拒绝非正数队列容量() {
        assertThrows(ConfigurationException.class,
                () -> ILinkClientConfig.builder().dispatchQueueCapacity(0).build());
    }

    @Test
    void 应拒绝不合理的登录轮询间隔() {
        assertThrows(ConfigurationException.class,
                () -> ILinkClientConfig.builder()
                        .loginTimeout(Duration.ofSeconds(5))
                        .loginPollInterval(Duration.ofSeconds(5))
                        .build());
    }

    @Test
    void 应拒绝普通请求超时不小于长轮询超时() {
        assertThrows(ConfigurationException.class,
                () -> ILinkClientConfig.builder()
                        .requestTimeout(Duration.ofSeconds(35))
                        .longPollTimeout(Duration.ofSeconds(35))
                        .build());
    }

    @Test
    void 应拒绝续约间隔不小于租约有效期() {
        assertThrows(ConfigurationException.class,
                () -> ILinkClientConfig.builder()
                        .leaseTtl(Duration.ofSeconds(10))
                        .leaseRenewInterval(Duration.ofSeconds(10))
                        .build());
    }

    @Test
    void 应输出只读且可安全诊断的最终配置() {
        ILinkClientConfig config = ILinkClientConfig.builder().build();

        assertEquals(Duration.ofSeconds(20), config.effectiveConfig().get("timeouts.request"));
        assertEquals(3, config.effectiveConfig().get("retry.maxAttempts"));
        assertTrue(config.toString().contains("dispatch.queueCapacity=1024"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.effectiveConfig().put("token", "secret"));
    }
}
