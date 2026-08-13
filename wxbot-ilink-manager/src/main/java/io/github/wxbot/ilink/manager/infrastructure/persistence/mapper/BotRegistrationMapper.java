/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.wxbot.ilink.manager.infrastructure.persistence.entity.BotRegistrationEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Bot 注册表 MyBatis-Plus Mapper。 */
public interface BotRegistrationMapper extends BaseMapper<BotRegistrationEntity> {
    /** 以数据库版本字段作为乐观锁推进状态。 */
    @Update("UPDATE wxbot_bot_registry SET status=#{status}, last_error=#{lastError}, "
            + "updated_at=#{updatedAt}, version=version+1 WHERE user_id=#{userId}")
    int updateStatus(@Param("userId") String userId, @Param("status") String status,
            @Param("lastError") String lastError, @Param("updatedAt") long updatedAt);

    /** 按创建时间稳定列出全部绑定。 */
    @Select("SELECT * FROM wxbot_bot_registry ORDER BY created_at,user_id")
    List<BotRegistrationEntity> selectAllOrdered();

    /** 仅从允许状态集合原子切换。 */
    @Update({"<script>UPDATE wxbot_bot_registry SET status=#{target},last_error=NULL,",
            "updated_at=#{updatedAt},version=version+1 WHERE user_id=#{userId} AND status IN",
            "<foreach collection='expected' item='item' open='(' separator=',' close=')'>#{item}</foreach>",
            "</script>"})
    int compareAndSetStatus(@Param("userId") String userId,
            @Param("expected") List<String> expected, @Param("target") String target,
            @Param("updatedAt") long updatedAt);

    /** 抢占二维码登录操作权。 */
    @Update({"<script>UPDATE wxbot_bot_registry SET status='LOGIN_PENDING',last_error=NULL,",
            "current_login_attempt_id=#{attemptId},updated_at=#{updatedAt},version=version+1 ",
            "WHERE user_id=#{userId} AND status IN",
            "<foreach collection='expected' item='item' open='(' separator=',' close=')'>#{item}</foreach>",
            "</script>"})
    int claimLogin(@Param("userId") String userId, @Param("expected") List<String> expected,
            @Param("attemptId") String attemptId, @Param("updatedAt") long updatedAt);

    /** 完成或终止当前二维码登录。 */
    @Update("UPDATE wxbot_bot_registry SET status=#{targetStatus},last_error=#{lastError},"
            + "updated_at=#{updatedAt},version=version+1 WHERE user_id=#{userId} "
            + "AND current_login_attempt_id=#{attemptId} AND status=#{expectedStatus}")
    int finishLogin(@Param("userId") String userId, @Param("attemptId") String attemptId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus, @Param("lastError") String lastError,
            @Param("updatedAt") long updatedAt);

    /** 删除业务绑定。 */
    @Delete("DELETE FROM wxbot_bot_registry WHERE user_id=#{userId}")
    int deleteByUserId(@Param("userId") String userId);

    /** 初始化注册表。 */
    @Update("CREATE TABLE IF NOT EXISTS wxbot_bot_registry (user_id VARCHAR(191) PRIMARY KEY,"
            + "client_key VARCHAR(255) NOT NULL UNIQUE,display_name VARCHAR(128) NOT NULL,"
            + "status VARCHAR(32) NOT NULL,last_error VARCHAR(255),created_at BIGINT NOT NULL,"
            + "updated_at BIGINT NOT NULL,version BIGINT NOT NULL,current_login_attempt_id VARCHAR(64))")
    void createTable();

    /** 兼容旧版本注册表。 */
    @Update("ALTER TABLE wxbot_bot_registry ADD COLUMN current_login_attempt_id VARCHAR(64) NULL")
    void addCurrentAttemptColumn();
}
