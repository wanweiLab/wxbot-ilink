/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.wxbot.ilink.manager.infrastructure.persistence.entity.BotLoginAttemptEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 二维码登录尝试 MyBatis-Plus Mapper。 */
public interface BotLoginAttemptMapper extends BaseMapper<BotLoginAttemptEntity> {
    /** 查询指定用户的一次登录尝试。 */
    @Select("SELECT * FROM wxbot_bot_login_attempt WHERE user_id=#{userId} AND attempt_id=#{attemptId}")
    BotLoginAttemptEntity selectForUser(
            @Param("userId") String userId, @Param("attemptId") String attemptId);

    /** 查询业务用户的当前登录尝试。 */
    @Select("SELECT a.* FROM wxbot_bot_registry r JOIN wxbot_bot_login_attempt a "
            + "ON a.attempt_id=r.current_login_attempt_id AND a.user_id=r.user_id "
            + "WHERE r.user_id=#{userId}")
    BotLoginAttemptEntity selectCurrent(@Param("userId") String userId);

    /** 更新二维码真实失效时间。 */
    @Update("UPDATE wxbot_bot_login_attempt a SET expires_at=#{expiresAt},"
            + "message=CASE WHEN phase='WAITING_SCAN' THEN '等待用户扫描二维码' ELSE message END,"
            + "updated_at=#{updatedAt},version=version+1 WHERE a.user_id=#{userId} "
            + "AND a.attempt_id=#{attemptId} AND a.phase IN ('WAITING_SCAN','SCANNED','CONFIRMED','BINDING') "
            + "AND EXISTS(SELECT 1 FROM wxbot_bot_registry r WHERE r.user_id=a.user_id "
            + "AND r.current_login_attempt_id=a.attempt_id AND r.status='LOGIN_PENDING')")
    int updateChallenge(@Param("userId") String userId, @Param("attemptId") String attemptId,
            @Param("expiresAt") long expiresAt, @Param("updatedAt") long updatedAt);

    /** 只允许从状态机声明的前置阶段向前推进。 */
    @Update({"<script>UPDATE wxbot_bot_login_attempt a SET phase=#{phase},message=#{message},",
            "updated_at=#{updatedAt},version=version+1 WHERE a.user_id=#{userId} ",
            "AND a.attempt_id=#{attemptId} AND a.phase IN",
            "<foreach collection='previous' item='item' open='(' separator=',' close=')'>#{item}</foreach>",
            "AND EXISTS(SELECT 1 FROM wxbot_bot_registry r WHERE r.user_id=a.user_id ",
            "AND r.current_login_attempt_id=a.attempt_id AND r.status='LOGIN_PENDING')</script>"})
    int updatePhase(@Param("userId") String userId, @Param("attemptId") String attemptId,
            @Param("phase") String phase, @Param("message") String message,
            @Param("previous") List<String> previous, @Param("updatedAt") long updatedAt);

    /** 原子结束当前尝试。 */
    @Update({"<script>UPDATE wxbot_bot_login_attempt SET phase=#{phase},message=#{message},",
            "registration_status=#{targetStatus},updated_at=#{updatedAt},version=version+1 ",
            "WHERE user_id=#{userId} AND attempt_id=#{attemptId} AND phase IN",
            "<foreach collection='previous' item='item' open='(' separator=',' close=')'>#{item}</foreach>",
            "AND EXISTS(SELECT 1 FROM wxbot_bot_registry r WHERE r.user_id=#{userId} ",
            "AND r.current_login_attempt_id=#{attemptId} AND r.status=#{expectedStatus})</script>"})
    int finish(@Param("userId") String userId, @Param("attemptId") String attemptId,
            @Param("phase") String phase, @Param("message") String message,
            @Param("targetStatus") String targetStatus, @Param("previous") List<String> previous,
            @Param("expectedStatus") String expectedStatus, @Param("updatedAt") long updatedAt);

    /** 清理用户全部历史登录尝试。 */
    @Delete("DELETE FROM wxbot_bot_login_attempt WHERE user_id=#{userId}")
    int deleteByUserId(@Param("userId") String userId);

    /** 初始化登录状态表。 */
    @Update("CREATE TABLE IF NOT EXISTS wxbot_bot_login_attempt (attempt_id VARCHAR(64) PRIMARY KEY,"
            + "user_id VARCHAR(191) NOT NULL,phase VARCHAR(32) NOT NULL,message VARCHAR(255),"
            + "registration_status VARCHAR(32) NOT NULL,expires_at BIGINT NOT NULL,"
            + "created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,version BIGINT NOT NULL)")
    void createTable();
}
