/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.domain.repository;

import io.github.wxbot.ilink.manager.BotLoginAttempt;
import io.github.wxbot.ilink.manager.BotLoginPhase;
import io.github.wxbot.ilink.manager.BotRegistration;
import io.github.wxbot.ilink.manager.BotStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Bot 绑定领域仓储接口，所有实现都必须保证 {@code userId} 全局唯一。 */
public interface BotRegistrationRepository {
    /** 创建唯一用户绑定；用户已经存在时必须失败。 */
    CompletionStage<BotRegistration> create(String userId, String clientKey, String displayName);
    /** 按业务用户查找绑定。 */
    CompletionStage<Optional<BotRegistration>> find(String userId);
    /** 列出全部绑定，供后台查询和启动恢复。 */
    CompletionStage<List<BotRegistration>> list();
    /** 原子更新状态并递增版本。 */
    CompletionStage<BotRegistration> updateStatus(String userId, BotStatus status, String lastError);
    /** 仅在当前状态符合预期时切换状态。 */
    CompletionStage<Boolean> compareAndSetStatus(
            String userId, Set<BotStatus> expected, BotStatus status);
    /** 原子创建并抢占一个新的二维码登录尝试。 */
    CompletionStage<Boolean> beginLogin(
            String userId, Set<BotStatus> expected, String attemptId, Instant expiresAt);
    /** 用服务端真实失效时间更新二维码登录尝试。 */
    CompletionStage<Boolean> updateLoginChallenge(
            String userId, String attemptId, Instant expiresAt);
    /** 按尝试标识查询登录进度。 */
    CompletionStage<Optional<BotLoginAttempt>> findLoginAttempt(String userId, String attemptId);
    /** 查询当前二维码登录尝试。 */
    CompletionStage<Optional<BotLoginAttempt>> findCurrentLoginAttempt(String userId);
    /** 更新当前登录尝试的非终态阶段。 */
    CompletionStage<Boolean> updateLoginPhase(
            String userId, String attemptId, BotLoginPhase phase, String safeMessage);
    /** 原子完成登录并把注册状态切换为在线。 */
    CompletionStage<Boolean> completeLogin(String userId, String attemptId);
    /** 原子结束失败或过期尝试。 */
    CompletionStage<Boolean> failLogin(
            String userId, String attemptId, BotLoginPhase phase,
            BotStatus targetStatus, String safeMessage);
    /** 删除业务用户绑定。 */
    CompletionStage<Void> delete(String userId);
}
