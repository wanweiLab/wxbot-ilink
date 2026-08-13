/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import io.github.wxbot.ilink.api.exception.ConfigurationException;
import io.github.wxbot.ilink.api.message.AcknowledgementMode;

/**
 * 客户端基础配置。
 *
 * <p>配置对象不可变且线程安全。所有时间配置都使用 {@link Duration}，避免毫秒、秒单位混用。构建时会一次性
 * 校验参数，运行期间不允许原地修改；动态调整能力将在独立的运行时策略接口中提供。
 */
public final class ILinkClientConfig {

    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration longPollTimeout;
    private final Duration loginTimeout;
    private final Duration loginPollInterval;
    private final Duration closeTimeout;
    private final Duration leaseTtl;
    private final Duration leaseRenewInterval;
    private final int dispatchStripes;
    private final int dispatchQueueCapacity;
    private final int maxAttempts;
    private final Duration messageProcessingTimeout;
    private final int maxDeliveryAttempts;
    private final AcknowledgementMode acknowledgementMode;
    private final Duration contextTtl;
    private final Duration retryBudgetWindow;
    private final double retryBudgetRatio;
    private final int retryBudgetMinimum;
    private final int retryBudgetMaximum;
    private final int circuitBreakerFailureThreshold;
    private final Duration circuitBreakerOpenDuration;

    private ILinkClientConfig(Builder builder) {
        this.connectTimeout = positive(builder.connectTimeout, "连接超时");
        this.requestTimeout = positive(builder.requestTimeout, "普通请求超时");
        this.longPollTimeout = positive(builder.longPollTimeout, "长轮询超时");
        this.loginTimeout = positive(builder.loginTimeout, "登录超时");
        this.loginPollInterval = positive(builder.loginPollInterval, "登录轮询间隔");
        this.closeTimeout = positive(builder.closeTimeout, "关闭超时");
        this.leaseTtl = positive(builder.leaseTtl, "租约有效期");
        this.leaseRenewInterval = positive(builder.leaseRenewInterval, "租约续约间隔");
        this.dispatchStripes = positive(builder.dispatchStripes, "分发分片数");
        this.dispatchQueueCapacity = positive(builder.dispatchQueueCapacity, "分发队列容量");
        this.maxAttempts = positive(builder.maxAttempts, "最大尝试次数");
        this.messageProcessingTimeout = positive(
                builder.messageProcessingTimeout, "消息处理超时");
        this.maxDeliveryAttempts = positive(builder.maxDeliveryAttempts, "最大投递次数");
        this.acknowledgementMode = Objects.requireNonNull(
                builder.acknowledgementMode, "确认模式不能为空");
        this.contextTtl = positive(builder.contextTtl, "上下文有效期");
        this.retryBudgetWindow = positive(builder.retryBudgetWindow, "重试预算窗口");
        if (!(builder.retryBudgetRatio > 0.0D && builder.retryBudgetRatio <= 1.0D)) {
            throw new ConfigurationException("重试预算比例必须大于 0 且不超过 1");
        }
        this.retryBudgetRatio = builder.retryBudgetRatio;
        this.retryBudgetMinimum = nonNegative(builder.retryBudgetMinimum, "最小重试预算");
        this.retryBudgetMaximum = positive(builder.retryBudgetMaximum, "最大重试预算");
        if (retryBudgetMinimum > retryBudgetMaximum) {
            throw new ConfigurationException("最小重试预算不能大于最大重试预算");
        }
        this.circuitBreakerFailureThreshold = positive(
                builder.circuitBreakerFailureThreshold, "熔断失败阈值");
        this.circuitBreakerOpenDuration = positive(
                builder.circuitBreakerOpenDuration, "熔断打开时间");

        if (loginPollInterval.compareTo(loginTimeout) >= 0) {
            throw new ConfigurationException("登录轮询间隔必须小于登录超时");
        }
        if (requestTimeout.compareTo(longPollTimeout) >= 0) {
            throw new ConfigurationException("普通请求超时必须小于长轮询超时");
        }
        if (leaseRenewInterval.compareTo(leaseTtl) >= 0) {
            throw new ConfigurationException("租约续约间隔必须小于租约有效期");
        }
    }

    /**
     * 创建带生产安全默认值的配置构建器。
     *
     * @return 配置构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 建立 TCP 连接的最长等待时间 */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** @return 普通业务请求的总超时时间 */
    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** @return 单次消息长轮询的总超时时间 */
    public Duration longPollTimeout() {
        return longPollTimeout;
    }

    /** @return 完整二维码登录流程的最长等待时间 */
    public Duration loginTimeout() {
        return loginTimeout;
    }

    /** @return 两次登录状态查询之间的基础间隔 */
    public Duration loginPollInterval() {
        return loginPollInterval;
    }

    /** @return 客户端关闭和资源释放的最长等待时间 */
    public Duration closeTimeout() {
        return closeTimeout;
    }

    /** @return 多实例运行租约的有效期 */
    public Duration leaseTtl() {
        return leaseTtl;
    }

    /** @return 两次租约续约之间的间隔 */
    public Duration leaseRenewInterval() {
        return leaseRenewInterval;
    }

    /** @return 消息分发器的固定分片数 */
    public int dispatchStripes() {
        return dispatchStripes;
    }

    /** @return 消息分发器允许排队的任务总数上限 */
    public int dispatchQueueCapacity() {
        return dispatchQueueCapacity;
    }

    /** @return 包含首次请求在内的最大尝试次数 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** @return 单次业务消息处理的最长时间 */
    public Duration messageProcessingTimeout() {
        return messageProcessingTimeout;
    }

    /** @return 消息进入死信前允许的最大投递次数 */
    public int maxDeliveryAttempts() {
        return maxDeliveryAttempts;
    }

    /** @return 消息成功处理后的确认模式 */
    public AcknowledgementMode acknowledgementMode() {
        return acknowledgementMode;
    }

    /** @return 会话上下文自最后更新时间起的有效期 */
    public Duration contextTtl() {
        return contextTtl;
    }

    /** @return 统计基础请求和已消费重试额度的滑动窗口 */
    public Duration retryBudgetWindow() {
        return retryBudgetWindow;
    }

    /** @return 重试额度占基础请求数量的最大比例 */
    public double retryBudgetRatio() {
        return retryBudgetRatio;
    }

    /** @return 低流量窗口可用的最小重试次数 */
    public int retryBudgetMinimum() {
        return retryBudgetMinimum;
    }

    /** @return 单个窗口可用的最大重试次数 */
    public int retryBudgetMaximum() {
        return retryBudgetMaximum;
    }

    /** @return 连续失败多少次后打开熔断器 */
    public int circuitBreakerFailureThreshold() {
        return circuitBreakerFailureThreshold;
    }

    /** @return 熔断器打开后进入半开探测前的等待时间 */
    public Duration circuitBreakerOpenDuration() {
        return circuitBreakerOpenDuration;
    }

    /**
     * 返回可安全写入日志和诊断接口的最终生效配置。
     *
     * <p>当前配置只包含资源边界和时间参数，不包含凭证；未来新增敏感项时必须在此方法中用
     * {@code ******} 替换，而不能直接暴露原值。返回映射不可修改且保持稳定顺序。
     */
    public Map<String, Object> effectiveConfig() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("timeouts.connect", connectTimeout);
        values.put("timeouts.request", requestTimeout);
        values.put("timeouts.longPoll", longPollTimeout);
        values.put("timeouts.login", loginTimeout);
        values.put("timeouts.loginPollInterval", loginPollInterval);
        values.put("timeouts.close", closeTimeout);
        values.put("lease.ttl", leaseTtl);
        values.put("lease.renewInterval", leaseRenewInterval);
        values.put("dispatch.stripes", dispatchStripes);
        values.put("dispatch.queueCapacity", dispatchQueueCapacity);
        values.put("dispatch.processingTimeout", messageProcessingTimeout);
        values.put("delivery.maxAttempts", maxDeliveryAttempts);
        values.put("delivery.acknowledgementMode", acknowledgementMode);
        values.put("context.ttl", contextTtl);
        values.put("retry.maxAttempts", maxAttempts);
        values.put("retry.budgetWindow", retryBudgetWindow);
        values.put("retry.budgetRatio", retryBudgetRatio);
        values.put("retry.budgetMinimum", retryBudgetMinimum);
        values.put("retry.budgetMaximum", retryBudgetMaximum);
        values.put("circuitBreaker.failureThreshold", circuitBreakerFailureThreshold);
        values.put("circuitBreaker.openDuration", circuitBreakerOpenDuration);
        return Collections.unmodifiableMap(values);
    }

    /** @return 不包含凭证的最终生效配置文本 */
    @Override
    public String toString() {
        return "ILinkClientConfig" + effectiveConfig();
    }

    private static Duration positive(Duration value, String name) {
        if (value == null) {
            throw new ConfigurationException(name + "不能为空");
        }
        if (value.isZero() || value.isNegative()) {
            throw new ConfigurationException(name + "必须大于零");
        }
        return value;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new ConfigurationException(name + "必须大于零");
        }
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new ConfigurationException(name + "不能为负数");
        }
        return value;
    }

    /**
     * 客户端配置构建器。
     *
     * <p>默认值偏向稳定运行，而非追求单次调用的最短等待时间。
     */
    public static final class Builder {

        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(20);
        private Duration longPollTimeout = Duration.ofSeconds(35);
        private Duration loginTimeout = Duration.ofMinutes(3);
        private Duration loginPollInterval = Duration.ofSeconds(1);
        private Duration closeTimeout = Duration.ofSeconds(10);
        private Duration leaseTtl = Duration.ofSeconds(30);
        private Duration leaseRenewInterval = Duration.ofSeconds(10);
        private int dispatchStripes = Math.max(2, Runtime.getRuntime().availableProcessors());
        private int dispatchQueueCapacity = 1024;
        private int maxAttempts = 3;
        private Duration messageProcessingTimeout = Duration.ofSeconds(30);
        private int maxDeliveryAttempts = 8;
        private AcknowledgementMode acknowledgementMode = AcknowledgementMode.AUTO;
        private Duration contextTtl = Duration.ofHours(24);
        private Duration retryBudgetWindow = Duration.ofMinutes(1);
        private double retryBudgetRatio = 0.2D;
        private int retryBudgetMinimum = 2;
        private int retryBudgetMaximum = 100;
        private int circuitBreakerFailureThreshold = 5;
        private Duration circuitBreakerOpenDuration = Duration.ofSeconds(30);

        private Builder() {
        }

        /** @param value 建立 TCP 连接的最长等待时间 */
        public Builder connectTimeout(Duration value) {
            this.connectTimeout = value;
            return this;
        }

        /** @param value 普通业务请求的总超时时间 */
        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        /** @param value 单次消息长轮询的总超时时间 */
        public Builder longPollTimeout(Duration value) {
            this.longPollTimeout = value;
            return this;
        }

        /** @param value 完整二维码登录流程的最长等待时间 */
        public Builder loginTimeout(Duration value) {
            this.loginTimeout = value;
            return this;
        }

        /** @param value 两次登录状态查询之间的基础间隔 */
        public Builder loginPollInterval(Duration value) {
            this.loginPollInterval = value;
            return this;
        }

        /** @param value 客户端关闭和资源释放的最长等待时间 */
        public Builder closeTimeout(Duration value) {
            this.closeTimeout = value;
            return this;
        }

        /** @param value 多实例运行租约的有效期 */
        public Builder leaseTtl(Duration value) {
            this.leaseTtl = value;
            return this;
        }

        /** @param value 两次租约续约之间的间隔，必须小于租约有效期 */
        public Builder leaseRenewInterval(Duration value) {
            this.leaseRenewInterval = value;
            return this;
        }

        /** @param value 消息分发器的固定分片数 */
        public Builder dispatchStripes(int value) {
            this.dispatchStripes = value;
            return this;
        }

        /** @param value 消息分发器允许排队的任务总数上限 */
        public Builder dispatchQueueCapacity(int value) {
            this.dispatchQueueCapacity = value;
            return this;
        }

        /** @param value 包含首次请求在内的最大尝试次数 */
        public Builder maxAttempts(int value) {
            this.maxAttempts = value;
            return this;
        }

        /** @param value 单次业务消息处理的最长时间 */
        public Builder messageProcessingTimeout(Duration value) {
            this.messageProcessingTimeout = value;
            return this;
        }

        /** @param value 消息进入死信前允许的最大投递次数 */
        public Builder maxDeliveryAttempts(int value) {
            this.maxDeliveryAttempts = value;
            return this;
        }

        /** @param value 消息成功处理后的确认模式 */
        public Builder acknowledgementMode(AcknowledgementMode value) {
            this.acknowledgementMode = value;
            return this;
        }

        /** @param value 会话上下文自最后更新时间起的有效期 */
        public Builder contextTtl(Duration value) {
            this.contextTtl = value;
            return this;
        }

        /** @param value 重试预算滑动窗口 */
        public Builder retryBudgetWindow(Duration value) {
            this.retryBudgetWindow = value;
            return this;
        }

        /** @param value 重试额度占基础请求数量的比例，范围为 (0, 1] */
        public Builder retryBudgetRatio(double value) {
            this.retryBudgetRatio = value;
            return this;
        }

        /** @param value 低流量窗口最少允许的重试次数 */
        public Builder retryBudgetMinimum(int value) {
            this.retryBudgetMinimum = value;
            return this;
        }

        /** @param value 单个窗口最多允许的重试次数 */
        public Builder retryBudgetMaximum(int value) {
            this.retryBudgetMaximum = value;
            return this;
        }

        /** @param value 打开熔断器所需的连续失败次数 */
        public Builder circuitBreakerFailureThreshold(int value) {
            this.circuitBreakerFailureThreshold = value;
            return this;
        }

        /** @param value 熔断打开后进入半开探测前的时间 */
        public Builder circuitBreakerOpenDuration(Duration value) {
            this.circuitBreakerOpenDuration = value;
            return this;
        }

        /**
         * 构建并校验不可变配置。
         *
         * @return 已校验的配置
         * @throws IllegalArgumentException 参数范围或参数关系不合法时抛出
         */
        public ILinkClientConfig build() {
            return new ILinkClientConfig(this);
        }
    }
}
