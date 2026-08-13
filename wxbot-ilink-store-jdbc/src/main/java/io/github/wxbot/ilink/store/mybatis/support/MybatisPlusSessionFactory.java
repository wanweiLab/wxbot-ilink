/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.store.mybatis.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import javax.sql.DataSource;
import java.util.Objects;

/** 为非 Spring SDK 模块创建轻量级 MyBatis-Plus 会话工厂。 */
public final class MybatisPlusSessionFactory {
    private MybatisPlusSessionFactory() { }

    /**
     * 创建并注册指定 Mapper。
     *
     * @param dataSource 数据源
     * @param mapperTypes Mapper 接口集合
     * @return 可用于显式事务的会话工厂
     */
    public static SqlSessionFactory create(DataSource dataSource, Class<?>... mapperTypes) {
        Environment environment = new Environment(
                "wxbot-ilink", new JdbcTransactionFactory(),
                Objects.requireNonNull(dataSource, "数据源不能为空"));
        MybatisConfiguration configuration = new MybatisConfiguration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapperType : mapperTypes) {
            configuration.addMapper(Objects.requireNonNull(mapperType, "Mapper 类型不能为空"));
        }
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
