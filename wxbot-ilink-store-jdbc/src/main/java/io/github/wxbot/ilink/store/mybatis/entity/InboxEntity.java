/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.store.mybatis.entity;

/** 加密可靠收件箱实体。 */
public class InboxEntity {
    private String clientKey;
    private Long messageId;
    private byte[] payload;
    private Long createdAt;
    private Integer attempt;
    private Long availableAt;
    private Long claimedUntil;
    private Boolean acknowledged;
    private Boolean deadLetter;
    private Long failedAt;
    private String lastError;

    public String getClientKey() { return clientKey; }
    public void setClientKey(String value) { this.clientKey = value; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long value) { this.messageId = value; }
    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] value) { this.payload = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer value) { this.attempt = value; }
    public Long getAvailableAt() { return availableAt; }
    public void setAvailableAt(Long value) { this.availableAt = value; }
    public Long getClaimedUntil() { return claimedUntil; }
    public void setClaimedUntil(Long value) { this.claimedUntil = value; }
    public Boolean getAcknowledged() { return acknowledged; }
    public void setAcknowledged(Boolean value) { this.acknowledged = value; }
    public Boolean getDeadLetter() { return deadLetter; }
    public void setDeadLetter(Boolean value) { this.deadLetter = value; }
    public Long getFailedAt() { return failedAt; }
    public void setFailedAt(Long value) { this.failedAt = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String value) { this.lastError = value; }
}
