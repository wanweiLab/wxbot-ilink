/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 消息游标实体。 */
@TableName("wxbot_ilink_cursor")
public class CursorEntity {
    @TableId
    private String clientKey;
    private String cursorValue;
    public String getClientKey() { return clientKey; }
    public void setClientKey(String value) { this.clientKey = value; }
    public String getCursorValue() { return cursorValue; }
    public void setCursorValue(String value) { this.cursorValue = value; }
}
