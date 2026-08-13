/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.testkit;

import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.api.transport.UpdateProtocol;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 可脚本化的内存协议，用于业务集成测试，不会访问微信网络。
 *
 * <p>测试可依次加入登录结果、更新批次或异常，并检查已发送请求。未配置更新时返回空批次且保持原 cursor。
 */
public final class ScriptedILinkProtocol implements LoginProtocol, UpdateProtocol, MessageProtocol {
    private final ConcurrentLinkedQueue<LoginPollResult> loginResults = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Object> updates = new ConcurrentLinkedQueue<>();
    private final List<SendMessageRequest> sentRequests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final BotSession session;
    private final Clock clock;

    public ScriptedILinkProtocol(BotSession session, Clock clock) {
        this.session = Objects.requireNonNull(session, "测试会话不能为空");
        this.clock = Objects.requireNonNull(clock, "测试时钟不能为空");
    }

    public ScriptedILinkProtocol enqueueLogin(LoginPollResult result) {
        loginResults.add(result);
        return this;
    }

    public ScriptedILinkProtocol enqueueUpdate(UpdateBatch batch) {
        updates.add(batch);
        return this;
    }

    public ScriptedILinkProtocol enqueueUpdateFailure(Throwable failure) {
        updates.add(Objects.requireNonNull(failure, "测试异常不能为空"));
        return this;
    }

    public List<SendMessageRequest> sentRequests() {
        return List.copyOf(sentRequests);
    }

    @Override
    public CompletionStage<QrCode> requestQrCode() {
        return CompletableFuture.completedFuture(new QrCode(
                "test-qr", "test-content", clock.instant().plusSeconds(300)));
    }

    @Override
    public CompletionStage<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
        LoginPollResult result = loginResults.poll();
        return CompletableFuture.completedFuture(result == null
                ? LoginPollResult.confirmed(session) : result);
    }

    @Override
    public CompletionStage<UpdateBatch> poll(BotSession ignored, String cursor) {
        Object next = updates.poll();
        if (next instanceof Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
        return CompletableFuture.completedFuture(next instanceof UpdateBatch batch
                ? batch : new UpdateBatch(cursor, List.of()));
    }

    @Override
    public CompletionStage<SendReceipt> send(BotSession ignored, SendMessageRequest request) {
        sentRequests.add(request);
        return CompletableFuture.completedFuture(new SendReceipt(
                request.clientId(), "test-message-" + sentRequests.size(), Instant.now(clock)));
    }

    @Override
    public CompletionStage<String> requestTypingTicket(
            BotSession ignored, String userId, String contextToken) {
        return CompletableFuture.completedFuture("test-ticket");
    }

    @Override
    public CompletionStage<Void> setTyping(
            BotSession ignored, String userId, String ticket, boolean typing) {
        return CompletableFuture.completedFuture(null);
    }
}
