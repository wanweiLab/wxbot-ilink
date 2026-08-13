/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.manager.domain.repository.BotRegistrationRepository;

/**
 * Bot 注册表兼容接口。
 *
 * <p>新代码可直接依赖领域层的 {@link BotRegistrationRepository}；本接口保留原有公开 API 名称。
 */
public interface BotRegistry extends BotRegistrationRepository { }
