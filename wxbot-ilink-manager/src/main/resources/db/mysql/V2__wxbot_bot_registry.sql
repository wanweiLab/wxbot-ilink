-- Copyright 2026 wxbot-ilink contributors
-- SPDX-License-Identifier: Apache-2.0
-- 一个业务 user_id 只能绑定一个 Bot；微信 botId 保存在加密快照中，不作为业务身份。

CREATE TABLE IF NOT EXISTS wxbot_bot_registry (
    user_id VARCHAR(191) PRIMARY KEY,
    client_key VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_error VARCHAR(255) NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_wxbot_bot_client_key (client_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
