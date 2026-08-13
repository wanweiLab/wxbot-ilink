/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** wxbot-ilink Spring Boot 基础设施配置。 */
@ConfigurationProperties("wxbot.ilink")
public class WxbotILinkProperties {
    private boolean enabled = true;
    private URI loginBaseUri = URI.create("https://ilinkai.weixin.qq.com");
    private String channelVersion = "1.0.0";
    private String routeTag;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(20);
    private Duration longPollTimeout = Duration.ofSeconds(35);
    private int maxRequests = 64;
    private int maxRequestsPerHost = 8;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
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
    public int getMaxRequests() { return maxRequests; }
    public void setMaxRequests(int value) { this.maxRequests = value; }
    public int getMaxRequestsPerHost() { return maxRequestsPerHost; }
    public void setMaxRequestsPerHost(int value) { this.maxRequestsPerHost = value; }
}
