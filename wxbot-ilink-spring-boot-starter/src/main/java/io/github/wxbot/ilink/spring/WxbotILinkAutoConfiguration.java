/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.spring;

import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.http.okhttp.ILinkOkHttpProtocol;
import io.github.wxbot.ilink.observability.MicrometerMetricsSink;
import io.micrometer.core.instrument.MeterRegistry;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;

/**
 * 自动装配 HTTP 协议与指标基础设施。
 *
 * <p>该配置刻意不自动创建或启动 Bot 客户端，因为可靠存储、客户端唯一键和消息处理器必须由业务明确选择。
 */
@AutoConfiguration
@EnableConfigurationProperties(WxbotILinkProperties.class)
@ConditionalOnProperty(prefix = "wxbot.ilink", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WxbotILinkAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "wxbotILinkOkHttpClient")
    OkHttpClient wxbotILinkOkHttpClient(WxbotILinkProperties properties) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(positive(properties.getMaxRequests(), "HTTP 最大并发数"));
        dispatcher.setMaxRequestsPerHost(positive(properties.getMaxRequestsPerHost(), "单主机最大并发数"));
        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(properties.getConnectTimeout())
                // 长轮询的实际截止时间由每个 Call 单独设置；共享客户端不能保留 10 秒默认读取超时。
                .readTimeout(properties.getLongPollTimeout())
                .writeTimeout(properties.getRequestTimeout())
                .callTimeout(properties.getLongPollTimeout())
                .retryOnConnectionFailure(false)
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ILinkOkHttpProtocol wxbotILinkProtocol(
            @Qualifier("wxbotILinkOkHttpClient") OkHttpClient client,
            WxbotILinkProperties properties) {
        return new ILinkOkHttpProtocol(client, properties.getLoginBaseUri(),
                properties.getChannelVersion(), properties.getRouteTag(), Clock.systemUTC(),
                properties.getRequestTimeout(), properties.getLongPollTimeout(), true);
    }

    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(MetricsSink.class)
    MetricsSink wxbotILinkMetrics(MeterRegistry registry) {
        return new MicrometerMetricsSink(registry);
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }
}
