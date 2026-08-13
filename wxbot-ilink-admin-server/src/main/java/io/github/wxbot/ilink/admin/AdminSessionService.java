/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的管理员登录会话。
 *
 * <p>账号密码来自后台配置，只在登录时做固定时间比较。随机令牌仅保存 SHA-256 摘要并在到期后清除，不把
 * 明文密码或令牌写入数据库和日志。多副本部署时应使用会话亲和，或替换成共享认证服务。
 */
@Service
public final class AdminSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] username;
    private final byte[] password;
    private final Duration tokenTtl;
    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AdminSessionService(AdminProperties properties) {
        this(properties, Clock.systemUTC());
    }

    private AdminSessionService(AdminProperties properties, Clock clock) {
        username = required(properties.getUsername(), "管理员账号").getBytes(StandardCharsets.UTF_8);
        password = required(properties.getPassword(), "管理员密码").getBytes(StandardCharsets.UTF_8);
        tokenTtl = positive(properties.getTokenTtl(), "登录令牌有效期");
        this.clock = clock;
    }

    /** 校验账号密码并签发随机令牌。 */
    public SessionToken login(String suppliedUsername, String suppliedPassword) {
        byte[] actualUsername = bytes(suppliedUsername);
        byte[] actualPassword = bytes(suppliedPassword);
        if (!MessageDigest.isEqual(username, actualUsername)
                || !MessageDigest.isEqual(password, actualPassword)) {
            throw new InvalidCredentialsException("账号或密码错误");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = clock.instant().plus(tokenTtl);
        sessions.put(digest(token), expiresAt);
        purgeExpired();
        return new SessionToken(token, expiresAt);
    }

    /** 检查 Bearer 令牌并延续到原到期时间。 */
    public boolean valid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String digest = digest(token);
        Instant expiresAt = sessions.get(digest);
        if (expiresAt == null || !expiresAt.isAfter(clock.instant())) {
            sessions.remove(digest);
            return false;
        }
        return true;
    }

    /** 让当前令牌立即失效。 */
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(digest(token));
        }
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private static String digest(String token) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(value);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("必须配置 " + name);
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
        return value;
    }

    /** @param token 随机访问令牌 @param expiresAt 到期时间 */
    public record SessionToken(String token, Instant expiresAt) { }
}
