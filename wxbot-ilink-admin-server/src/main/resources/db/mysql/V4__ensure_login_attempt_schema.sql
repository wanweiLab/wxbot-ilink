-- Copyright 2026 wxbot-ilink contributors
-- SPDX-License-Identifier: Apache-2.0
-- 兼容升级脚本：可重复执行，只补齐二维码登录状态所需结构，不修改既有业务数据。

SET @wxbot_schema = DATABASE();
SET @wxbot_column_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @wxbot_schema
      AND TABLE_NAME = 'wxbot_bot_registry'
      AND COLUMN_NAME = 'current_login_attempt_id'
);
SET @wxbot_add_column_sql = IF(
    @wxbot_column_exists = 0,
    'ALTER TABLE wxbot_bot_registry ADD COLUMN current_login_attempt_id VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE wxbot_add_column_statement FROM @wxbot_add_column_sql;
EXECUTE wxbot_add_column_statement;
DEALLOCATE PREPARE wxbot_add_column_statement;

-- 登录尝试只保存流程状态，不保存二维码、微信身份、令牌或其他敏感凭证。
CREATE TABLE IF NOT EXISTS wxbot_bot_login_attempt (
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
