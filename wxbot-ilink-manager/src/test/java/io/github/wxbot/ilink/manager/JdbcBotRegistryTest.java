/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CompletionException;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JDBC Bot 注册表的 MySQL 兼容模式测试。 */
class JdbcBotRegistryTest {
    private JdbcBotRegistry registry;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:bot-registry-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        this.dataSource = dataSource;
        registry = new JdbcBotRegistry(this.dataSource);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void 一个用户只能创建一个Bot() {
        BotRegistration first = registry.create("user-1", "client-1", "一号机器人")
                .toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () ->
                registry.create("user-1", "client-2", "重复机器人").toCompletableFuture().join());

        assertInstanceOf(BotConflictException.class, failure.getCause());
        assertEquals(BotStatus.LOGIN_REQUIRED, first.status());
        assertEquals(1, registry.list().toCompletableFuture().join().size());
    }

    @Test
    void 状态更新不影响另一个用户() {
        registry.create("user-a", "client-a", "甲").toCompletableFuture().join();
        registry.create("user-b", "client-b", "乙").toCompletableFuture().join();

        BotRegistration updated = registry.updateStatus("user-a", BotStatus.ONLINE, null)
                .toCompletableFuture().join();

        assertEquals(BotStatus.ONLINE, updated.status());
        assertEquals(1L, updated.version());
        assertEquals(BotStatus.LOGIN_REQUIRED,
                registry.find("user-b").toCompletableFuture().join().orElseThrow().status());
        assertTrue(registry.find("user-a").toCompletableFuture().join().isPresent());
    }

    @Test
    void 扫码状态只能被一个后台副本抢占() {
        registry.create("user-lock", "client-lock", "抢占测试").toCompletableFuture().join();
        Instant fallbackExpiry = Instant.ofEpochMilli(
                Instant.now().plusSeconds(300).toEpochMilli());

        boolean first = registry.beginLogin(
                        "user-lock", Set.of(BotStatus.LOGIN_REQUIRED), "attempt-1", fallbackExpiry)
                .toCompletableFuture().join();
        boolean second = registry.beginLogin(
                        "user-lock", Set.of(BotStatus.LOGIN_REQUIRED), "attempt-2", fallbackExpiry)
                .toCompletableFuture().join();

        assertTrue(first);
        assertEquals(false, second);
        BotLoginAttempt attempt = registry.findCurrentLoginAttempt("user-lock")
                .toCompletableFuture().join().orElseThrow();
        assertEquals("attempt-1", attempt.attemptId());
        assertEquals(BotLoginPhase.WAITING_SCAN, attempt.phase());
        assertEquals(fallbackExpiry, attempt.expiresAt());
    }

    @Test
    void 扫码确认与后台绑定阶段可以跨副本查询() {
        registry.create("user-login", "client-login", "登录状态").toCompletableFuture().join();
        Instant expiresAt = Instant.parse("2026-08-13T10:00:00Z");

        assertTrue(registry.beginLogin("user-login", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-login", Instant.now().plusSeconds(300)).toCompletableFuture().join());
        assertTrue(registry.updateLoginChallenge("user-login", "attempt-login", expiresAt)
                .toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-login", "attempt-login",
                BotLoginPhase.SCANNED, "用户已扫码").toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-login", "attempt-login",
                BotLoginPhase.CONFIRMED, "用户已确认").toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-login", "attempt-login",
                BotLoginPhase.BINDING, "正在保存加密会话").toCompletableFuture().join());
        assertTrue(registry.completeLogin("user-login", "attempt-login")
                .toCompletableFuture().join());

        BotLoginAttempt attempt = registry.findLoginAttempt("user-login", "attempt-login")
                .toCompletableFuture().join().orElseThrow();
        assertEquals(BotLoginPhase.BOUND, attempt.phase());
        assertEquals(BotStatus.ONLINE, attempt.registrationStatus());
        assertEquals(expiresAt, attempt.expiresAt());
        assertEquals(BotStatus.ONLINE, registry.find("user-login")
                .toCompletableFuture().join().orElseThrow().status());
    }

    @Test
    void 旧登录尝试不能覆盖新的当前尝试() {
        registry.create("user-stale", "client-stale", "旧尝试隔离")
                .toCompletableFuture().join();
        assertTrue(registry.beginLogin("user-stale", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-old", Instant.now().plusSeconds(300)).toCompletableFuture().join());
        assertTrue(registry.failLogin("user-stale", "attempt-old", BotLoginPhase.FAILED,
                BotStatus.ERROR, "第一次失败").toCompletableFuture().join());
        assertTrue(registry.beginLogin("user-stale", Set.of(BotStatus.ERROR),
                "attempt-new", Instant.now().plusSeconds(300)).toCompletableFuture().join());

        assertFalse(registry.updateLoginPhase("user-stale", "attempt-old",
                BotLoginPhase.SCANNED, "迟到的扫码事件").toCompletableFuture().join());
        assertFalse(registry.failLogin("user-stale", "attempt-old", BotLoginPhase.EXPIRED,
                BotStatus.LOGIN_REQUIRED, "迟到的过期事件").toCompletableFuture().join());

        BotLoginAttempt current = registry.findCurrentLoginAttempt("user-stale")
                .toCompletableFuture().join().orElseThrow();
        assertEquals("attempt-new", current.attemptId());
        assertEquals(BotLoginPhase.WAITING_SCAN, current.phase());
        assertEquals(BotStatus.LOGIN_PENDING, registry.find("user-stale")
                .toCompletableFuture().join().orElseThrow().status());
    }

    @Test
    void 二维码失效时间可以在快速扫码后补写且不会被覆盖() {
        registry.create("user-fast", "client-fast", "快速扫码")
                .toCompletableFuture().join();
        assertTrue(registry.beginLogin("user-fast", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-fast", Instant.now().plusSeconds(300)).toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-fast", "attempt-fast",
                BotLoginPhase.SCANNED, "用户已扫码").toCompletableFuture().join());
        Instant firstExpiry = Instant.parse("2026-08-13T10:00:00Z");
        assertTrue(registry.updateLoginChallenge("user-fast", "attempt-fast", firstExpiry)
                .toCompletableFuture().join());
        assertTrue(registry.updateLoginChallenge("user-fast", "attempt-fast",
                firstExpiry.plusSeconds(30)).toCompletableFuture().join());

        BotLoginAttempt attempt = registry.findCurrentLoginAttempt("user-fast")
                .toCompletableFuture().join().orElseThrow();
        assertEquals(BotLoginPhase.SCANNED, attempt.phase());
        assertEquals(firstExpiry.plusSeconds(30), attempt.expiresAt());
    }

    @Test
    void 微信确认后迟到的二维码过期不能覆盖绑定进度() {
        registry.create("user-confirmed", "client-confirmed", "确认后过期隔离")
                .toCompletableFuture().join();
        assertTrue(registry.beginLogin("user-confirmed", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-confirmed", Instant.now().plusSeconds(300)).toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-confirmed", "attempt-confirmed",
                BotLoginPhase.CONFIRMED, "用户已确认").toCompletableFuture().join());

        assertFalse(registry.failLogin("user-confirmed", "attempt-confirmed",
                BotLoginPhase.EXPIRED, BotStatus.LOGIN_REQUIRED, "迟到的过期事件")
                .toCompletableFuture().join());

        BotLoginAttempt attempt = registry.findCurrentLoginAttempt("user-confirmed")
                .toCompletableFuture().join().orElseThrow();
        assertEquals(BotLoginPhase.CONFIRMED, attempt.phase());
        assertEquals(BotStatus.LOGIN_PENDING, registry.find("user-confirmed")
                .toCompletableFuture().join().orElseThrow().status());
    }

    @Test
    void 已绑定会话失效会原子降级登录尝试和注册状态() {
        registry.create("user-invalid", "client-invalid", "失效降级")
                .toCompletableFuture().join();
        assertTrue(registry.beginLogin("user-invalid", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-invalid", Instant.now().plusSeconds(300)).toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-invalid", "attempt-invalid",
                BotLoginPhase.CONFIRMED, "用户已确认").toCompletableFuture().join());
        assertTrue(registry.updateLoginPhase("user-invalid", "attempt-invalid",
                BotLoginPhase.BINDING, "正在绑定").toCompletableFuture().join());
        assertTrue(registry.completeLogin("user-invalid", "attempt-invalid")
                .toCompletableFuture().join());

        assertTrue(registry.failLogin("user-invalid", "attempt-invalid",
                BotLoginPhase.FAILED, BotStatus.LOGIN_REQUIRED, "微信会话已经失效")
                .toCompletableFuture().join());

        BotLoginAttempt attempt = registry.findCurrentLoginAttempt("user-invalid")
                .toCompletableFuture().join().orElseThrow();
        assertEquals(BotLoginPhase.FAILED, attempt.phase());
        assertEquals(BotStatus.LOGIN_REQUIRED, attempt.registrationStatus());
        assertEquals(BotStatus.LOGIN_REQUIRED, registry.find("user-invalid")
                .toCompletableFuture().join().orElseThrow().status());
    }

    @Test
    void 删除业务绑定会同步清理全部登录尝试() throws Exception {
        registry.create("user-delete", "client-delete", "解绑清理")
                .toCompletableFuture().join();
        assertTrue(registry.beginLogin("user-delete", Set.of(BotStatus.LOGIN_REQUIRED),
                "attempt-delete", Instant.now().plusSeconds(300)).toCompletableFuture().join());

        registry.delete("user-delete").toCompletableFuture().join();

        assertTrue(registry.find("user-delete").toCompletableFuture().join().isEmpty());
        assertEquals(0, countAttempts("user-delete"));
    }

    @Test
    void 关闭自动建表时会在启动阶段拒绝旧版结构() throws Exception {
        JdbcDataSource legacyDataSource = new JdbcDataSource();
        legacyDataSource.setURL("jdbc:h2:mem:legacy-bot-registry-" + java.util.UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = legacyDataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE wxbot_bot_registry ("
                    + "user_id VARCHAR(191) PRIMARY KEY,client_key VARCHAR(255) NOT NULL UNIQUE,"
                    + "display_name VARCHAR(128) NOT NULL,status VARCHAR(32) NOT NULL,"
                    + "last_error VARCHAR(255),created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,"
                    + "version BIGINT NOT NULL)");
        }

        BotOperationException failure = assertThrows(BotOperationException.class,
                () -> new JdbcBotRegistry(legacyDataSource, java.time.Clock.systemUTC(),
                        1, 8, false));

        assertTrue(failure.getMessage().contains("V4__ensure_login_attempt_schema.sql"));
    }

    private int countAttempts(String userId) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                connection.prepareStatement("SELECT COUNT(*) FROM wxbot_bot_login_attempt WHERE user_id=?")) {
            statement.setString(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}
