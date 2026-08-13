-- Copyright 2026 wxbot-ilink contributors
-- SPDX-License-Identifier: Apache-2.0
-- MySQL 8.0+：SDK 会话、可靠收件箱与运行租约。

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
