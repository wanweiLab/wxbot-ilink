/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import io.github.wxbot.ilink.api.ILinkClient;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.observability.ClientHealth;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateChangedEvent;
import io.github.wxbot.ilink.api.state.ClientStateListener;
import io.github.wxbot.ilink.manager.BotRuntimeManager;
import io.github.wxbot.ilink.manager.BotLoginPhase;
import io.github.wxbot.ilink.manager.JdbcBotRegistry;
import io.github.wxbot.ilink.manager.ManagedBotClient;
import io.github.wxbot.ilink.store.jdbc.JdbcILinkStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理 REST 路由与共享令牌边界测试。 */
class BotAdminControllerTest {
    private BotRuntimeManager manager;
    private JdbcBotRegistry registry;
    private JdbcILinkStore store;
    private MockMvc mvc;
    private String authorization;
    private TestLoginClient loginClient;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:admin-controller-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry = new JdbcBotRegistry(dataSource);
        store = new JdbcILinkStore(dataSource, new byte[32]);
        loginClient = new TestLoginClient(store);
        manager = new BotRuntimeManager(registry, store, (userId, clientKey) -> {
            loginClient.clientKey = clientKey;
            return new ManagedBotClient(loginClient, () -> { });
        });
        AdminProperties properties = new AdminProperties();
        properties.setUsername("admin");
        properties.setPassword("test-password");
        AdminSessionService sessions = new AdminSessionService(properties);
        String token = sessions.login("admin", "test-password").token();
        mvc = MockMvcBuilders.standaloneSetup(new BotAdminController(manager))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new AdminAuthenticationFilter(sessions))
                .build();
        authorization = "Bearer " + token;
    }

    @AfterEach
    void tearDown() {
        manager.close();
        registry.close();
        store.close();
    }

    @Test
    void 缺少登录令牌时拒绝访问() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/api/users/bots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void 同一用户绑定接口返回创建结果() throws Exception {
        MvcResult pending = mvc.perform(MockMvcRequestBuilders.post("/api/users/user-1/bot")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"displayName\":\"我的 Bot\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.clientKey").value(org.hamcrest.Matchers.startsWith("user:")))
                .andExpect(jsonPath("$.status").value("LOGIN_REQUIRED"));
    }

    @Test
    void 登录接口返回attemptId且状态接口感知扫码和绑定身份() throws Exception {
        MvcResult binding = mvc.perform(MockMvcRequestBuilders.post("/api/users/user-login/bot")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"displayName\":\"扫码 Bot\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(binding)).andExpect(status().isCreated());

        MvcResult login = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/user-login/bot/login")
                        .header("Authorization", authorization))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").isNotEmpty())
                .andExpect(jsonPath("$.phase").value("WAITING_SCAN"))
                .andReturn().getResponse().getContentAsString();
        String attemptId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("attemptId").asText();

        loginClient.emit(ClientState.QR_SCANNED);
        MvcResult scanned = mvc.perform(MockMvcRequestBuilders.get(
                        "/api/users/user-login/bot/login/" + attemptId)
                        .header("Authorization", authorization))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(scanned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("SCANNED"))
                .andExpect(jsonPath("$.wechatUserId").doesNotExist());

        loginClient.confirm();
        for (int index = 0; index < 100; index++) {
            if (manager.getLoginStatus("user-login", attemptId).toCompletableFuture().join().phase()
                    == BotLoginPhase.BOUND) {
                break;
            }
            Thread.onSpinWait();
        }
        MvcResult bound = mvc.perform(MockMvcRequestBuilders.get(
                        "/api/users/user-login/bot/login/" + attemptId)
                        .header("Authorization", authorization))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(bound))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("BOUND"))
                .andExpect(jsonPath("$.registrationStatus").value("ONLINE"))
                .andExpect(jsonPath("$.wechatUserId").value("wx-user"))
                .andExpect(jsonPath("$.botId").value("wx-bot"));
    }

    @Test
    void 测试消息忽略请求中的任意接收者并发送给加密快照微信用户() throws Exception {
        bindAndCompleteLogin("message-user");

        MvcResult sending = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/message-user/bot/messages/test")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"text\":\"后台测试\",\"toUserId\":\"attacker-selected-user\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(sending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").isNotEmpty());
        assertEquals(1, loginClient.sentRequests.size());
        assertEquals("wx-user", loginClient.sentRequests.get(0).toUserId());
        assertEquals("后台测试", loginClient.sentRequests.get(0).payload().get("text"));
    }

    @Test
    void 未在线Bot发送测试消息映射为稳定冲突响应() throws Exception {
        MvcResult binding = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/offline-message/bot")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"displayName\":\"尚未登录\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(binding)).andExpect(status().isCreated());

        MvcResult sending = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/offline-message/bot/messages/test")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"text\":\"不能发送\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(sending))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOT_CONFLICT"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertEquals(0, loginClient.sentRequests.size());
    }

    @Test
    void 旧文本接口不能再由管理端指定任意微信接收者() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/any-user/bot/messages/text")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"toUserId\":\"arbitrary-user\",\"text\":\"禁止\"}"))
                .andExpect(status().isNotFound());
    }

    private void bindAndCompleteLogin(String userId) throws Exception {
        MvcResult binding = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/" + userId + "/bot")
                        .header("Authorization", authorization)
                        .contentType("application/json")
                        .content("{\"displayName\":\"消息 Bot\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(binding)).andExpect(status().isCreated());

        MvcResult login = mvc.perform(MockMvcRequestBuilders.post(
                        "/api/users/" + userId + "/bot/login")
                        .header("Authorization", authorization))
                .andExpect(request().asyncStarted()).andReturn();
        String body = mvc.perform(asyncDispatch(login))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String attemptId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("attemptId").asText();
        loginClient.confirm();
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.getLoginStatus(userId, attemptId).toCompletableFuture().join().phase()
                    == BotLoginPhase.BOUND) {
                return;
            }
            Thread.sleep(1L);
        }
        throw new AssertionError("测试 Bot 没有完成在线绑定");
    }

    /** 受控完成登录的最小客户端，用于验证 REST 状态契约。 */
    private static final class TestLoginClient implements ILinkClient {
        private final JdbcILinkStore store;
        private final BotSession session = new BotSession(
                "secret", "wx-user", "wx-bot", URI.create("https://example.test"));
        private final CompletableFuture<BotSession> loginCompletion = new CompletableFuture<>();
        private final List<ClientStateListener> listeners = new ArrayList<>();
        private final List<SendMessageRequest> sentRequests = new ArrayList<>();
        private String clientKey;

        private TestLoginClient(JdbcILinkStore store) { this.store = store; }

        @Override public ClientState state() { return ClientState.QR_WAITING; }
        @Override public ClientHealth health() { return new ClientHealth(
                state(), Instant.now(), null, 0, null, 0, 0, null); }
        @Override public void addStateListener(ClientStateListener listener) { listeners.add(listener); }
        @Override public Flow.Publisher<MessageDelivery> messages() { return subscriber -> { }; }
        @Override public CompletionStage<LoginAttempt> login() {
            return CompletableFuture.completedFuture(new LoginAttempt() {
                @Override public QrCode qrCode() { return new QrCode(
                        "token", "https://qr.example", Instant.now().plusSeconds(60)); }
                @Override public CompletionStage<BotSession> completion() {
                    return loginCompletion;
                }
                @Override public boolean cancel() { return false; }
            });
        }
        @Override public CompletionStage<Boolean> restore() { return CompletableFuture.completedFuture(false); }
        @Override public CompletionStage<SendReceipt> send(SendMessageRequest request) {
            sentRequests.add(request);
            return CompletableFuture.completedFuture(new SendReceipt(
                    request.clientId(), "server-message", Instant.now()));
        }
        @Override public CompletionStage<SendReceipt> sendWithTyping(
                SendMessageRequest request, Duration duration) { return send(request); }
        @Override public CompletionStage<Optional<ClientSnapshot>> saveSnapshot() {
            ClientSnapshot snapshot = new ClientSnapshot(
                    ClientSnapshot.CURRENT_SCHEMA_VERSION, session, "", Map.of(), Instant.now());
            return store.save(clientKey, snapshot).thenApply(ignored -> Optional.of(snapshot));
        }
        @Override public void close() { }

        private void emit(ClientState state) {
            ClientStateChangedEvent event = new ClientStateChangedEvent(
                    1L, ClientState.QR_WAITING, state, "REST 测试状态", Instant.now());
            listeners.forEach(listener -> listener.onStateChanged(event));
        }

        private void confirm() { loginCompletion.complete(session); }
    }
}
