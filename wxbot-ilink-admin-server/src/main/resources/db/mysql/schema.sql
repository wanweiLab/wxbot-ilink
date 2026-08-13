-- Copyright 2026 wxbot-ilink contributors
-- SPDX-License-Identifier: Apache-2.0
-- 一次性执行本文件后再启动后台。生产升级应由 Flyway 或 Liquibase 管理版本。

CREATE TABLE IF NOT EXISTS wxbot_ilink_snapshot (
    client_key VARCHAR(255) PRIMARY KEY,
    payload LONGBLOB NOT NULL,
    saved_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wxbot_ilink_cursor (
    client_key VARCHAR(255) PRIMARY KEY,
    cursor_value VARCHAR(4096) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wxbot_ilink_inbox (
    client_key VARCHAR(255) NOT NULL,
    message_id BIGINT NOT NULL,
    payload LONGBLOB NOT NULL,
    created_at BIGINT NOT NULL,
    attempt INT NOT NULL,
    available_at BIGINT NOT NULL,
    claimed_until BIGINT NULL,
    acknowledged BOOLEAN NOT NULL,
    dead_letter BOOLEAN NOT NULL,
    failed_at BIGINT NULL,
    last_error VARCHAR(255) NULL,
    PRIMARY KEY (client_key, message_id),
    KEY wxbot_ilink_inbox_pending (client_key, acknowledged, available_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wxbot_ilink_lease (
    client_key VARCHAR(255) PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    expires_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS wxbot_bot_registry (
    user_id VARCHAR(191) PRIMARY KEY,
    client_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(255) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL,
    current_login_attempt_id VARCHAR(64) NULL,
    UNIQUE KEY uk_wxbot_bot_client_key (client_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 登录尝试只保存状态元数据；微信身份和 token 只存在于上方 AES-GCM 加密快照。
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
