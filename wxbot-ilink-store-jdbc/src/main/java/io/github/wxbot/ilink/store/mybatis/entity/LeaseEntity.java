/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 单活租约实体。 */
@TableName("wxbot_ilink_lease")
public class LeaseEntity {
    @TableId
    private String clientKey;
    private String ownerId;
    private Long expiresAt;
    public String getClientKey() { return clientKey; }
    public void setClientKey(String value) { this.clientKey = value; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String value) { this.ownerId = value; }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long value) { this.expiresAt = value; }
}
