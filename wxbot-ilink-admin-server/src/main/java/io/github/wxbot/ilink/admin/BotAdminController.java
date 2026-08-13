/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.manager.BotLoginChallenge;
import io.github.wxbot.ilink.manager.BotLoginStatusView;
import io.github.wxbot.ilink.manager.BotRegistration;
import io.github.wxbot.ilink.manager.BotRuntimeView;
import io.github.wxbot.ilink.manager.application.BotManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;

/** 按业务 userId 暴露的一用户一 Bot 管理接口。 */
@RestController
@RequestMapping("/api/users")
public class BotAdminController {
    private final BotManagementService manager;

    public BotAdminController(BotManagementService manager) {
        this.manager = manager;
    }

    /** 创建唯一用户绑定；不会立即发起扫码。 */
    @PostMapping("/{userId}/bot")
    @ResponseStatus(HttpStatus.CREATED)
    public CompletionStage<BotRegistration> bind(
            @PathVariable String userId, @RequestBody BindBotRequest request) {
        return manager.bind(userId, request.displayName());
    }

    /** 列出全部 Bot，不包含 token、二维码或微信身份。 */
    @GetMapping("/bots")
    public CompletionStage<List<BotRuntimeView>> list() {
        return manager.list();
    }

    /** 查询一个用户的 Bot 状态和健康信息。 */
    @GetMapping("/{userId}/bot")
    public CompletionStage<BotRuntimeView> get(@PathVariable String userId) {
        return manager.get(userId);
    }

    /** 首次绑定或会话失效时生成二维码。 */
    @PostMapping("/{userId}/bot/login")
    public CompletionStage<LoginResponse> login(@PathVariable String userId) {
        return manager.login(userId).thenApply(challenge -> new LoginResponse(
                challenge.attemptId(), challenge.imageContent(), challenge.expiresAt(),
                "WAITING_SCAN"));
    }

    /** 查询指定二维码的扫码、微信确认和后台绑定进度。 */
    @GetMapping("/{userId}/bot/login/{attemptId}")
    public CompletionStage<BotLoginStatusView> loginStatus(
            @PathVariable String userId, @PathVariable String attemptId) {
        return manager.getLoginStatus(userId, attemptId);
    }

    /** 使用首次扫码保存的快照恢复，正常启动无需再次扫码。 */
    @PostMapping("/{userId}/bot/restore")
    public CompletionStage<RestoreResponse> restore(@PathVariable String userId) {
        return manager.restore(userId).thenApply(RestoreResponse::new);
    }

    /** 向当前业务用户已绑定的微信身份发送测试文本。 */
    @PostMapping("/{userId}/bot/messages/test")
    public CompletionStage<SendReceipt> sendTestMessage(
            @PathVariable String userId, @RequestBody SendTestMessageRequest request) {
        return manager.sendTestMessage(userId, request.text());
    }

    /** 保存快照并停止运行时，绑定仍保留。 */
    @PostMapping("/{userId}/bot/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public CompletionStage<Void> stop(@PathVariable String userId) {
        return manager.stop(userId);
    }

    /** 解绑并清除该用户的全部 SDK 数据，下次使用需要重新扫码。 */
    @DeleteMapping("/{userId}/bot")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public CompletionStage<Void> unbind(@PathVariable String userId) {
        return manager.unbind(userId);
    }

    /** @param displayName Bot 展示名称 */
    public record BindBotRequest(String displayName) { }

    /**
     * @param attemptId 登录尝试唯一标识
     * @param imageContent 二维码内容
     * @param expiresAt 失效时间
     * @param phase 当前阶段
     */
    public record LoginResponse(
            String attemptId, String imageContent, Instant expiresAt, String phase) { }

    /** @param restored 是否从已有会话恢复 */
    public record RestoreResponse(boolean restored) { }

    /** @param text 测试文本内容；接收者固定为当前业务用户扫码绑定的微信身份 */
    public record SendTestMessageRequest(String text) { }
}
