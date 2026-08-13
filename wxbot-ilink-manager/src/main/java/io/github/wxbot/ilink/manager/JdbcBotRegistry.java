/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.manager.infrastructure.persistence.repository.MybatisPlusBotRegistrationRepository;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Bot 注册表公开兼容入口。
 *
 * <p>实际 MyBatis-Plus 实现位于基础设施仓储包，保留本类可避免已有接入方因内部分包调整而修改代码。
 */
public final class JdbcBotRegistry extends MybatisPlusBotRegistrationRepository {
    /** 使用默认有界线程池并初始化表。 */
    public JdbcBotRegistry(DataSource dataSource) {
        super(dataSource);
    }

    /** 创建可配置的注册表。 */
    public JdbcBotRegistry(
            DataSource dataSource, Clock clock, int workerCount, int queueCapacity,
            boolean initializeSchema) {
        super(dataSource, clock, workerCount, queueCapacity, initializeSchema);
    }
}
