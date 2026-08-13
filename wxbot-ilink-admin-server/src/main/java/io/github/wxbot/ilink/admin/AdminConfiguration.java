/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.core.WxbotILinkClient;
import io.github.wxbot.ilink.http.okhttp.ILinkOkHttpProtocol;
import io.github.wxbot.ilink.manager.BotClientFactory;
import io.github.wxbot.ilink.manager.BotMessageHandlerFactory;
import io.github.wxbot.ilink.manager.BotRuntimeManager;
import io.github.wxbot.ilink.manager.JdbcBotRegistry;
import io.github.wxbot.ilink.manager.ManagedBotClient;
import io.github.wxbot.ilink.store.jdbc.JdbcILinkStore;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/** 多 Bot 后台基础设施装配。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminProperties.class)
public class AdminConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdminConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    JdbcILinkStore jdbcILinkStore(DataSource dataSource, AdminProperties properties) {
        return new JdbcILinkStore(
                dataSource, masterKey(properties), positive(properties.getStoreWorkers(), "存储线程数"),
                positive(properties.getStoreQueueCapacity(), "存储队列容量"), properties.isInitializeSchema());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    JdbcBotRegistry jdbcBotRegistry(DataSource dataSource, AdminProperties properties) {
        return new JdbcBotRegistry(
                dataSource, Clock.systemUTC(), positive(properties.getRegistryWorkers(), "注册表线程数"),
                positive(properties.getRegistryQueueCapacity(), "注册表队列容量"),
                properties.isInitializeSchema());
    }

    @Bean
    @ConditionalOnMissingBean
    OkHttpClient wxbotAdminOkHttpClient(AdminProperties properties) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(positive(properties.getMaxRequests(), "HTTP 最大并发数"));
        dispatcher.setMaxRequestsPerHost(positive(
                properties.getMaxRequestsPerHost(), "单主机最大并发数"));
        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(properties.getConnectTimeout())
                // 长轮询的实际截止时间由每个 Call 单独设置；共享客户端不能保留 10 秒默认读取超时。
                .readTimeout(properties.getLongPollTimeout())
                .writeTimeout(properties.getRequestTimeout())
                .retryOnConnectionFailure(false)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    BotMessageHandlerFactory botMessageHandlerFactory() {
        return (userId, clientKey) -> delivery -> CompletableFuture.completedFuture(null);
    }

    @Bean
    @ConditionalOnMissingBean
    BotClientFactory botClientFactory(
            OkHttpClient httpClient,
            JdbcILinkStore store,
            BotMessageHandlerFactory handlerFactory,
            AdminProperties properties) {
        ILinkClientConfig config = ILinkClientConfig.builder()
                .connectTimeout(properties.getConnectTimeout())
                .requestTimeout(properties.getRequestTimeout())
                .longPollTimeout(properties.getLongPollTimeout())
                .loginTimeout(properties.getLoginTimeout())
                .loginPollInterval(properties.getLoginPollInterval())
                .dispatchStripes(positive(properties.getDispatchStripes(), "消息分发分片数"))
                .dispatchQueueCapacity(positive(
                        properties.getDispatchQueueCapacity(), "消息分发队列容量"))
                .build();
        return (userId, clientKey) -> {
            ILinkOkHttpProtocol protocol = new ILinkOkHttpProtocol(
                    httpClient, properties.getLoginBaseUri(), properties.getChannelVersion(),
                    routeTag(properties, clientKey), Clock.systemUTC(), properties.getRequestTimeout(),
                    properties.getLongPollTimeout(), false);
            WxbotILinkClient client = WxbotILinkClient.builder()
                    .clientKey(clientKey)
                    .config(config)
                    .protocols(protocol, protocol, protocol)
                    .stateStore(store)
                    .inboxStore(store)
                    .leaseStore(store)
                    .leaseOwnerId(required(properties.getInstanceId(), "实例标识"))
                    .messageHandler(handlerFactory.create(userId, clientKey))
                    .build();
            return new ManagedBotClient(client, protocol);
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    BotRuntimeManager botRuntimeManager(
            JdbcBotRegistry registry, JdbcILinkStore store, BotClientFactory factory) {
        return new BotRuntimeManager(registry, store, factory);
    }

    @Bean
    ApplicationRunner restoreBotsOnStartup(BotRuntimeManager manager, AdminProperties properties) {
        return ignored -> {
            if (properties.isRestoreOnStartup()) {
                LOGGER.info("开始按顺序恢复已绑定 Bot");
                manager.restoreAll().whenComplete((users, failure) -> {
                    if (failure == null) {
                        LOGGER.info("启动恢复完成，restoredCount={}", users.size());
                    } else {
                        LOGGER.error("启动恢复 Bot 失败", failure);
                    }
                });
            }
        };
    }

    /** 应用关闭时统一释放共享 OkHttp 的线程池、连接池和缓存。 */
    @Bean
    AutoCloseable wxbotAdminHttpShutdown(OkHttpClient client) {
        return () -> {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            if (client.cache() != null) {
                client.cache().close();
            }
        };
    }

    private static byte[] masterKey(AdminProperties properties) {
        String encoded = required(properties.getMasterKeyBase64(), "存储主密钥");
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 16 && key.length != 24 && key.length != 32) {
                throw new IllegalArgumentException("存储主密钥解码后必须为 16、24 或 32 字节");
            }
            return key;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("存储主密钥必须是有效 Base64，且解码后为 AES 合法长度", failure);
        }
    }

    private static String routeTag(AdminProperties properties, String clientKey) {
        String prefix = properties.getRouteTag();
        return (prefix == null || prefix.isBlank()) ? clientKey : prefix + ":" + clientKey;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }
}
