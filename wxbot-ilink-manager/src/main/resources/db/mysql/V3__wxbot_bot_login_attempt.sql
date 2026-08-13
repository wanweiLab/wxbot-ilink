-- Copyright 2026 wxbot-ilink contributors
-- SPDX-License-Identifier: Apache-2.0
-- 在既有一用户一 Bot 注册表上增加可跨后台副本查询的二维码登录状态。

ALTER TABLE wxbot_bot_registry
    ADD COLUMN current_login_attempt_id VARCHAR(64) NULL;

-- 只保存扫码流程状态元数据，不保存二维码、微信身份或任何凭证。
CREATE TABLE wxbot_bot_login_attempt (
    attempt_id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(191) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    message VARCHAR(255) NULL,
    registration_status VARCHAR(32) NOT NULL,
    expires_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL,
    KEY ix_wxbot_login_attempt_user_updated (user_id, updated_at),
    CONSTRAINT fk_wxbot_login_attempt_user
        FOREIGN KEY (user_id) REFERENCES wxbot_bot_registry(user_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
