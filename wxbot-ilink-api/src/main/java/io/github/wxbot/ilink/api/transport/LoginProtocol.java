/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.transport;

import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;

import java.util.concurrent.CompletionStage;

/**
 * iLink 登录协议传输扩展点。
 *
 * <p>实现负责 HTTP、序列化和协议错误转换，但不负责轮询调度和客户端状态推进。
 */
public interface LoginProtocol {

    /** @return 新二维码响应 */
    CompletionStage<QrCode> requestQrCode();

    /**
     * 查询二维码状态。
     *
     * @param qrCodeToken 二维码不透明令牌
     * @return 单次查询结果
     */
    CompletionStage<LoginPollResult> queryQrCodeStatus(String qrCodeToken);
}
