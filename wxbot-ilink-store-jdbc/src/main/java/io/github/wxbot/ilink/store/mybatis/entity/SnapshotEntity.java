/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.store.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** AES-GCM 加密会话快照实体。 */
@TableName("wxbot_ilink_snapshot")
public class SnapshotEntity {
    @TableId
    private String clientKey;
    private byte[] payload;
    private Long savedAt;

    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }
    public Long getSavedAt() { return savedAt; }
    public void setSavedAt(Long savedAt) { this.savedAt = savedAt; }
}
