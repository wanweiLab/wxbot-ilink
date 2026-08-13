/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 二维码登录尝试持久化实体，仅保存无敏感信息的状态元数据。 */
@TableName("wxbot_bot_login_attempt")
public class BotLoginAttemptEntity {
    @TableId
    private String attemptId;
    private String userId;
    private String phase;
    private String message;
    private String registrationStatus;
    private Long expiresAt;
    private Long createdAt;
    private Long updatedAt;
    private Long version;

    public String getAttemptId() { return attemptId; }
    public void setAttemptId(String attemptId) { this.attemptId = attemptId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getRegistrationStatus() { return registrationStatus; }
    public void setRegistrationStatus(String value) { this.registrationStatus = value; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
