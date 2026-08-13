/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import io.github.wxbot.ilink.api.ILinkClient;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.OutboundMessageType;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.observability.ClientHealth;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateListener;
import io.github.wxbot.ilink.store.jdbc.JdbcILinkStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 多 Bot 生命周期、一次扫码恢复与隔离测试。 */
class BotRuntimeManagerTest {
    private JdbcBotRegistry registry;
    private JdbcILinkStore store;
    private BotRuntimeManager manager;
    private RecordingFactory factory;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:bot-manager-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry = new JdbcBotRegistry(dataSource);
        store = new JdbcILinkStore(dataSource, new byte[32]);
        factory = new RecordingFactory(store);
        manager = new BotRuntimeManager(registry, store, factory);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        registry.close();
        store.close();
    }

    @Test
    void 两个用户使用不同隔离键和独立运行时() {
        BotRegistration first = manager.bind("user-a", "甲").toCompletableFuture().join();
        BotRegistration second = manager.bind("user-b", "乙").toCompletableFuture().join();

        manager.login("user-a").toCompletableFuture().join().completion().toCompletableFuture().join();
        manager.login("user-b").toCompletableFuture().join().completion().toCompletableFuture().join();

        assertNotEquals(first.clientKey(), second.clientKey());
        assertEquals(List.of(first.clientKey(), second.clientKey()), factory.clientKeys);
        assertTrue(manager.get("user-a").toCompletableFuture().join().running());
        assertTrue(manager.get("user-b").toCompletableFuture().join().running());
    }

    @Test
    void 首次扫码保存后重启只恢复不再次扫码() {
        manager.bind("user-once", "只扫一次").toCompletableFuture().join();
        BotLoginChallenge challenge = manager.login("user-once").toCompletableFuture().join();
        FakeClient first = factory.nextClient;
        challenge.completion().toCompletableFuture().join();
        assertEquals(1, first.loginCalls);
        assertEquals(1, first.snapshotCalls);

        manager.stop("user-once").toCompletableFuture().join();
        factory.restoreResult = true;
        assertTrue(manager.restore("user-once").toCompletableFuture().join());

        FakeClient restored = factory.created.get(1);
        assertEquals(0, restored.loginCalls);
        assertEquals(1, restored.restoreCalls);
    }

    @Test
    void 重复绑定同一用户失败() {
        manager.bind("same-user", "第一条").toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                manager.bind("same-user", "第二条").toCompletableFuture().join());

        assertInstanceOf(BotConflictException.class, failure.getCause());
    }

    @Test
    void 解绑一个用户不会停止另一个用户() {
        BotRegistration removed = manager.bind("remove-me", "删除").toCompletableFuture().join();
        manager.bind("keep-me", "保留").toCompletableFuture().join();
        manager.login("remove-me").toCompletableFuture().join().completion().toCompletableFuture().join();
        manager.login("keep-me").toCompletableFuture().join().completion().toCompletableFuture().join();

        manager.unbind("remove-me").toCompletableFuture().join();

        assertFalse(registry.find("remove-me").toCompletableFuture().join().isPresent());
        assertTrue(manager.get("keep-me").toCompletableFuture().join().running());
        assertTrue(store.load(removed.clientKey()).toCompletableFuture().join().isEmpty());
    }

    @Test
    void 绑定完成状态返回微信身份且登录表不保存身份() {
        manager.bind("identity-user", "身份绑定").toCompletableFuture().join();

        BotLoginChallenge challenge = manager.login("identity-user")
                .toCompletableFuture().join();
        challenge.completion().toCompletableFuture().join();
        BotLoginStatusView status = manager.getLoginStatus(
                "identity-user", challenge.attemptId()).toCompletableFuture().join();
        BotLoginAttempt persisted = registry.findLoginAttempt(
                "identity-user", challenge.attemptId()).toCompletableFuture().join().orElseThrow();

        assertEquals(BotLoginPhase.BOUND, status.phase());
        assertEquals(BotStatus.ONLINE, status.registrationStatus());
        assertEquals("wx-user", status.wechatUserId());
        assertEquals("changing-bot-id", status.botId());
        assertFalse(persisted.toString().contains("wx-user"));
        assertFalse(persisted.toString().contains("changing-bot-id"));
        assertFalse(status.toString().contains("wx-user"));
        assertFalse(status.toString().contains("changing-bot-id"));
    }

    @Test
    void 扫码事件会推进为等待微信确认() {
        factory.autoCompleteLogin = false;
        manager.bind("scan-user", "扫码感知").toCompletableFuture().join();
        BotLoginChallenge challenge = manager.login("scan-user")
                .toCompletableFuture().join();

        factory.nextClient.emit(ClientState.QR_SCANNED);
        BotLoginStatusView status = awaitPhase(
                "scan-user", challenge.attemptId(), BotLoginPhase.SCANNED);

        assertEquals("用户已扫码，请在微信中确认", status.message());
        assertEquals(null, status.wechatUserId());
        assertEquals(null, status.botId());
    }

    @Test
    void 微信确认后按顺序完成加密快照与业务绑定() {
        factory.autoCompleteLogin = false;
        manager.bind("confirm-user", "确认绑定").toCompletableFuture().join();
        BotLoginChallenge challenge = manager.login("confirm-user")
                .toCompletableFuture().join();

        factory.nextClient.emit(ClientState.QR_SCANNED);
        factory.nextClient.confirmLogin();
        challenge.completion().toCompletableFuture().join();

        assertEquals(BotLoginPhase.BOUND, manager.getLoginStatus(
                "confirm-user", challenge.attemptId()).toCompletableFuture().join().phase());
        assertEquals(1, factory.nextClient.snapshotCalls);
        assertEquals(BotStatus.ONLINE, registry.find("confirm-user")
                .toCompletableFuture().join().orElseThrow().status());
    }

    @Test
    void 微信确认后跨过二维码期限仍会完成绑定() {
        factory.autoCompleteLogin = false;
        factory.autoCompleteSnapshot = false;
        manager.bind("slow-snapshot", "慢快照绑定").toCompletableFuture().join();
        BotLoginChallenge challenge = manager.login("slow-snapshot")
                .toCompletableFuture().join();

        factory.nextClient.confirmLogin();
        awaitPhase("slow-snapshot", challenge.attemptId(), BotLoginPhase.BINDING);
        assertTrue(registry.updateLoginChallenge(
                "slow-snapshot", challenge.attemptId(), Instant.EPOCH)
                .toCompletableFuture().join());

        assertEquals(BotLoginPhase.BINDING, manager.getLoginStatus(
                "slow-snapshot", challenge.attemptId()).toCompletableFuture().join().phase());
        factory.nextClient.releaseSnapshot();
        challenge.completion().toCompletableFuture().join();
        assertEquals(BotLoginPhase.BOUND, manager.getLoginStatus(
                "slow-snapshot", challenge.attemptId()).toCompletableFuture().join().phase());
    }

    @Test
    void 测试消息接收者只取自AESGCM持久快照() {
        BotRegistration registration = manager.bind("message-user", "测试消息")
                .toCompletableFuture().join();
        manager.login("message-user").toCompletableFuture().join()
                .completion().toCompletableFuture().join();
        FakeClient runtime = factory.nextClient;
        BotSession persistedSession = new BotSession(
                "persisted-secret", "persisted-wx-user", "persisted-bot-id",
                URI.create("https://persisted.example.test"));
        store.save(registration.clientKey(), new ClientSnapshot(
                        ClientSnapshot.CURRENT_SCHEMA_VERSION, persistedSession,
                        "", Map.of(), Instant.now()))
                .toCompletableFuture().join();

        SendReceipt receipt = manager.sendTestMessage("message-user", "来自管理后台的测试")
                .toCompletableFuture().join();

        assertEquals(1, runtime.sentRequests.size());
        SendMessageRequest request = runtime.sentRequests.get(0);
        assertEquals("persisted-wx-user", request.toUserId());
        assertNotEquals("wx-user", request.toUserId());
        assertEquals(OutboundMessageType.TEXT, request.type());
        assertEquals("来自管理后台的测试", request.payload().get("text"));
        assertFalse(request.clientId().isBlank());
        assertEquals(request.clientId(), receipt.clientId());
    }

    @Test
    void 登录未完成即使本实例已有运行时也拒绝测试消息() {
        factory.autoCompleteLogin = false;
        manager.bind("pending-message", "登录中").toCompletableFuture().join();
        manager.login("pending-message").toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                manager.sendTestMessage("pending-message", "不能发送")
                        .toCompletableFuture().join());

        assertInstanceOf(BotConflictException.class, failure.getCause());
        assertTrue(factory.nextClient.sentRequests.isEmpty());
    }

    @Test
    void 注册状态在线但本实例没有运行时仍拒绝测试消息() {
        BotRegistration registration = manager.bind("remote-runtime", "其他实例运行")
                .toCompletableFuture().join();
        registry.updateStatus("remote-runtime", BotStatus.ONLINE, null)
                .toCompletableFuture().join();
        store.save(registration.clientKey(), new ClientSnapshot(
                        ClientSnapshot.CURRENT_SCHEMA_VERSION,
                        new BotSession("secret", "wx-remote", "bot-remote",
                                URI.create("https://example.test")),
                        "", Map.of(), Instant.now()))
                .toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                manager.sendTestMessage("remote-runtime", "不能跨实例发送")
                        .toCompletableFuture().join());

        assertInstanceOf(BotConflictException.class, failure.getCause());
        assertTrue(factory.created.isEmpty());
    }

    @Test
    void 会话在绑定提交窗口失效不会留下假在线状态() {
        manager.close();
        CompletableFuture<Void> committed = new CompletableFuture<>();
        CompletableFuture<Void> releaseResult = new CompletableFuture<>();
        BotRegistry delayedRegistry = (BotRegistry) Proxy.newProxyInstance(
                BotRegistry.class.getClassLoader(), new Class<?>[] { BotRegistry.class },
                (proxy, method, arguments) -> {
                    Object result = method.invoke(registry, arguments);
                    if (!method.getName().equals("completeLogin")) {
                        return result;
                    }
                    @SuppressWarnings("unchecked")
                    CompletionStage<Boolean> completion = (CompletionStage<Boolean>) result;
                    return completion.thenCompose(completed -> {
                        committed.complete(null);
                        return releaseResult.thenApply(ignored -> completed);
                    });
                });
        manager = new BotRuntimeManager(delayedRegistry, store, factory);
        factory.autoCompleteLogin = false;
        manager.bind("expire-on-bind", "提交窗口失效").toCompletableFuture().join();
        BotLoginChallenge challenge = manager.login("expire-on-bind")
                .toCompletableFuture().join();

        factory.nextClient.confirmLogin();
        committed.join();
        factory.nextClient.emit(ClientState.EXPIRED);
        releaseResult.complete(null);

        assertThrows(CompletionException.class,
                () -> challenge.completion().toCompletableFuture().join());
        assertEquals(BotStatus.LOGIN_REQUIRED, registry.find("expire-on-bind")
                .toCompletableFuture().join().orElseThrow().status());
        assertEquals(BotLoginPhase.FAILED, registry.findCurrentLoginAttempt("expire-on-bind")
                .toCompletableFuture().join().orElseThrow().phase());
        assertFalse(manager.get("expire-on-bind").toCompletableFuture().join().running());
    }

    private BotLoginStatusView awaitPhase(
            String userId, String attemptId, BotLoginPhase expected) {
        for (int index = 0; index < 100; index++) {
            BotLoginStatusView status = manager.getLoginStatus(userId, attemptId)
                    .toCompletableFuture().join();
            if (status.phase() == expected) {
                return status;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("登录状态没有推进到 " + expected);
    }

    /** 记录每个用户创建出的独立客户端。 */
    private static final class RecordingFactory implements BotClientFactory {
        private final JdbcILinkStore store;
        private final List<String> clientKeys = new ArrayList<>();
        private final List<FakeClient> created = new ArrayList<>();
        private boolean restoreResult;
        private boolean autoCompleteLogin = true;
        private boolean autoCompleteSnapshot = true;
        private FakeClient nextClient;

        private RecordingFactory(JdbcILinkStore store) {
            this.store = store;
        }

        @Override
        public ManagedBotClient create(String userId, String clientKey) {
            clientKeys.add(clientKey);
            nextClient = new FakeClient(
                    store, clientKey, restoreResult, autoCompleteLogin, autoCompleteSnapshot);
            created.add(nextClient);
            return new ManagedBotClient(nextClient, () -> { });
        }
    }

    /** 只实现管理器测试所需行为的确定性客户端。 */
    private static final class FakeClient implements ILinkClient {
        private final JdbcILinkStore store;
        private final String clientKey;
        private final boolean restoreResult;
        private final boolean autoCompleteLogin;
        private final boolean autoCompleteSnapshot;
        private final BotSession session = new BotSession(
                "secret-token", "wx-user", "changing-bot-id", URI.create("https://example.test"));
        private final CompletableFuture<BotSession> loginCompletion = new CompletableFuture<>();
        private final CompletableFuture<Void> snapshotRelease = new CompletableFuture<>();
        private final List<ClientStateListener> stateListeners = new ArrayList<>();
        private final List<SendMessageRequest> sentRequests = new ArrayList<>();
        private int loginCalls;
        private int restoreCalls;
        private int snapshotCalls;
        private boolean closed;

        private FakeClient(
                JdbcILinkStore store, String clientKey,
                boolean restoreResult, boolean autoCompleteLogin,
                boolean autoCompleteSnapshot) {
            this.store = store;
            this.clientKey = clientKey;
            this.restoreResult = restoreResult;
            this.autoCompleteLogin = autoCompleteLogin;
            this.autoCompleteSnapshot = autoCompleteSnapshot;
        }

        @Override public ClientState state() { return closed ? ClientState.CLOSED : ClientState.CONNECTED; }
        @Override public ClientHealth health() { return new ClientHealth(
                state(), Instant.EPOCH, null, 0, null, 0, 0, null); }
        @Override public void addStateListener(ClientStateListener listener) {
            stateListeners.add(listener);
        }
        @Override public Flow.Publisher<MessageDelivery> messages() { return subscriber -> { }; }
        @Override public CompletionStage<LoginAttempt> login() {
            loginCalls++;
            return CompletableFuture.completedFuture(new LoginAttempt() {
                @Override public QrCode qrCode() { return new QrCode(
                        "qr-token", "https://qr.example", Instant.now().plusSeconds(60)); }
                @Override public CompletionStage<BotSession> completion() {
                    return autoCompleteLogin
                            ? CompletableFuture.completedFuture(session) : loginCompletion;
                }
                @Override public boolean cancel() { return false; }
            });
        }
        @Override public CompletionStage<Boolean> restore() {
            restoreCalls++;
            return CompletableFuture.completedFuture(restoreResult);
        }
        @Override public CompletionStage<SendReceipt> send(SendMessageRequest request) {
            sentRequests.add(request);
            return CompletableFuture.completedFuture(new SendReceipt(
                    request.clientId(), "server-id", Instant.now()));
        }
        @Override public CompletionStage<SendReceipt> sendWithTyping(
                SendMessageRequest request, Duration typingDuration) { return send(request); }
        @Override public CompletionStage<Optional<ClientSnapshot>> saveSnapshot() {
            snapshotCalls++;
            ClientSnapshot snapshot = new ClientSnapshot(
                    ClientSnapshot.CURRENT_SCHEMA_VERSION, session, "", Map.of(), Instant.now());
            CompletionStage<Void> ready = autoCompleteSnapshot
                    ? CompletableFuture.completedFuture(null) : snapshotRelease;
            return ready.thenCompose(ignored -> store.save(clientKey, snapshot))
                    .thenApply(ignored -> Optional.of(snapshot));
        }
        @Override public void close() { closed = true; }

        private void confirmLogin() {
            loginCompletion.complete(session);
        }

        private void releaseSnapshot() {
            snapshotRelease.complete(null);
        }

        private void emit(ClientState state) {
            io.github.wxbot.ilink.api.state.ClientStateChangedEvent event =
                    new io.github.wxbot.ilink.api.state.ClientStateChangedEvent(
                            1L, ClientState.QR_WAITING, state, "测试状态变更", Instant.now());
            stateListeners.forEach(listener -> listener.onStateChanged(event));
        }
    }
}
