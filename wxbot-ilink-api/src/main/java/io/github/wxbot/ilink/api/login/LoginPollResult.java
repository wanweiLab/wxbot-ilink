/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.login;

import io.github.wxbot.ilink.api.session.BotSession;

/**
 * 一次登录状态查询结果。
 *
 * @param phase 登录阶段
 * @param session 登录确认后返回的会话，其他阶段必须为空
 */
public record LoginPollResult(LoginPhase phase, BotSession session) {

    public LoginPollResult {
        if (phase == null) {
            throw new IllegalArgumentException("登录阶段不能为空");
        }
        if (phase == LoginPhase.CONFIRMED && session == null) {
            throw new IllegalArgumentException("登录确认结果必须包含会话");
        }
        if (phase != LoginPhase.CONFIRMED && session != null) {
            throw new IllegalArgumentException("未确认登录时不能携带会话");
        }
    }

    /** @return 等待扫码结果 */
    public static LoginPollResult waiting() {
        return new LoginPollResult(LoginPhase.WAITING, null);
    }

    /** @return 已扫码结果 */
    public static LoginPollResult scanned() {
        return new LoginPollResult(LoginPhase.SCANNED, null);
    }

    /** @param session 已建立会话；@return 登录确认结果 */
    public static LoginPollResult confirmed(BotSession session) {
        return new LoginPollResult(LoginPhase.CONFIRMED, session);
    }

    /** @return 二维码过期结果 */
    public static LoginPollResult expired() {
        return new LoginPollResult(LoginPhase.EXPIRED, null);
    }
}
