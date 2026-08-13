/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.application;

import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.manager.BotLoginChallenge;
import io.github.wxbot.ilink.manager.BotLoginStatusView;
import io.github.wxbot.ilink.manager.BotRegistration;
import io.github.wxbot.ilink.manager.BotRuntimeView;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** 管理后台使用的 Bot 应用服务接口，隔离 Web 层与运行时具体实现。 */
public interface BotManagementService {
    CompletionStage<BotRegistration> bind(String userId, String displayName);
    CompletionStage<List<BotRuntimeView>> list();
    CompletionStage<BotRuntimeView> get(String userId);
    CompletionStage<BotLoginChallenge> login(String userId);
    CompletionStage<BotLoginStatusView> getLoginStatus(String userId, String attemptId);
    CompletionStage<Boolean> restore(String userId);
    CompletionStage<SendReceipt> send(String userId, SendMessageRequest request);
    CompletionStage<SendReceipt> sendTestMessage(String userId, String text);
    CompletionStage<Void> stop(String userId);
    CompletionStage<Void> unbind(String userId);
    CompletionStage<List<String>> restoreAll();
}
