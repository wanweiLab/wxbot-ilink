/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 多 Bot 后台资源、安全和 iLink 协议配置。 */
@ConfigurationProperties("wxbot.admin")
public class AdminProperties {
    private String masterKeyBase64;
    private String instanceId = "wxbot-admin-local";
    private String username = "admin";
    private String password;
    private Duration tokenTtl = Duration.ofHours(8);
    private URI loginBaseUri = URI.create("https://ilinkai.weixin.qq.com");
    private String channelVersion = "1.0.0";
    private String routeTag;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration longPollTimeout = Duration.ofSeconds(35);
    private Duration loginTimeout = Duration.ofMinutes(3);
    private Duration loginPollInterval = Duration.ofSeconds(1);
    private int maxRequests = 128;
    private int maxRequestsPerHost = 32;
    private int dispatchStripes = 2;
    private int dispatchQueueCapacity = 256;
    private int storeWorkers = 8;
    private int storeQueueCapacity = 2048;
    private int registryWorkers = 2;
    private int registryQueueCapacity = 512;
    private boolean initializeSchema;
    private boolean restoreOnStartup = true;

    public String getMasterKeyBase64() { return masterKeyBase64; }
    public void setMasterKeyBase64(String value) { this.masterKeyBase64 = value; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { this.instanceId = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { this.username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { this.password = value; }
    public Duration getTokenTtl() { return tokenTtl; }
    public void setTokenTtl(Duration value) { this.tokenTtl = value; }
    public URI getLoginBaseUri() { return loginBaseUri; }
    public void setLoginBaseUri(URI value) { this.loginBaseUri = value; }
    public String getChannelVersion() { return channelVersion; }
    public void setChannelVersion(String value) { this.channelVersion = value; }
    public String getRouteTag() { return routeTag; }
    public void setRouteTag(String value) { this.routeTag = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { this.connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { this.requestTimeout = value; }
    public Duration getLongPollTimeout() { return longPollTimeout; }
    public void setLongPollTimeout(Duration value) { this.longPollTimeout = value; }
    public Duration getLoginTimeout() { return loginTimeout; }
    public void setLoginTimeout(Duration value) { this.loginTimeout = value; }
    public Duration getLoginPollInterval() { return loginPollInterval; }
    public void setLoginPollInterval(Duration value) { this.loginPollInterval = value; }
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int value) { this.maxRequests = value; }
    public int getMaxRequestsPerHost() { return maxRequestsPerHost; }
    public void setMaxRequestsPerHost(int value) { this.maxRequestsPerHost = value; }
    public int getDispatchStripes() { return dispatchStripes; }
    public void setDispatchStripes(int value) { this.dispatchStripes = value; }
    public int getDispatchQueueCapacity() { return dispatchQueueCapacity; }
    public void setDispatchQueueCapacity(int value) { this.dispatchQueueCapacity = value; }
    public int getStoreWorkers() { return storeWorkers; }
    public void setStoreWorkers(int value) { this.storeWorkers = value; }
    public int getStoreQueueCapacity() { return storeQueueCapacity; }
    public void setStoreQueueCapacity(int value) { this.storeQueueCapacity = value; }
    public int getRegistryWorkers() { return registryWorkers; }
    public void setRegistryWorkers(int value) { this.registryWorkers = value; }
    public int getRegistryQueueCapacity() { return registryQueueCapacity; }
    public void setRegistryQueueCapacity(int value) { this.registryQueueCapacity = value; }
    public boolean isInitializeSchema() { return initializeSchema; }
    public void setInitializeSchema(boolean value) { this.initializeSchema = value; }
    public boolean isRestoreOnStartup() { return restoreOnStartup; }
    public void setRestoreOnStartup(boolean value) { this.restoreOnStartup = value; }
}
