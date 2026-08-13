/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.manager.infrastructure.persistence.repository;

import io.github.wxbot.ilink.manager.BotConflictException;
import io.github.wxbot.ilink.manager.BotLoginAttempt;
import io.github.wxbot.ilink.manager.BotLoginPhase;
import io.github.wxbot.ilink.manager.BotNotFoundException;
import io.github.wxbot.ilink.manager.BotOperationException;
import io.github.wxbot.ilink.manager.BotRegistration;
import io.github.wxbot.ilink.manager.BotRegistry;
import io.github.wxbot.ilink.manager.BotStatus;
import io.github.wxbot.ilink.manager.infrastructure.persistence.entity.BotLoginAttemptEntity;
import io.github.wxbot.ilink.manager.infrastructure.persistence.entity.BotRegistrationEntity;
import io.github.wxbot.ilink.manager.infrastructure.persistence.mapper.BotLoginAttemptMapper;
import io.github.wxbot.ilink.manager.infrastructure.persistence.mapper.BotRegistrationMapper;
import io.github.wxbot.ilink.store.mybatis.support.MybatisPlusSessionFactory;
import io.github.wxbot.ilink.store.mybatis.support.AbstractBoundedAsyncRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 基于 MyBatis-Plus 的 Bot 绑定注册表实现。
 *
 * <p>Mapper 独立位于 {@code infrastructure.persistence.mapper} 包，领域接口仍由 {@link BotRegistry}
 * 定义。阻塞数据库操作运行在有界线程池中；登录抢占和终态提交使用显式事务，保证多后台副本下的一用户一 Bot。
 * 注册表不保存二维码、微信身份或令牌。
 */
public class MybatisPlusBotRegistrationRepository extends AbstractBoundedAsyncRepository
        implements BotRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            MybatisPlusBotRegistrationRepository.class);

    private final SqlSessionFactory sessionFactory;
    private final Clock clock;

    /** 使用两个工作线程、256 个排队任务并自动初始化表。 */
    public MybatisPlusBotRegistrationRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC(), 2, 256, true);
    }

    /** 创建可配置的 MyBatis-Plus 注册表。 */
    public MybatisPlusBotRegistrationRepository(
            DataSource dataSource, Clock clock, int workerCount, int queueCapacity,
            boolean initializeSchema) {
        super("wxbot-ilink-bot-registry", workerCount, queueCapacity);
        this.sessionFactory = MybatisPlusSessionFactory.create(
                dataSource, BotRegistrationMapper.class, BotLoginAttemptMapper.class);
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        try {
            if (initializeSchema) {
                initializeSchema();
            }
            validateSchema(dataSource);
        } catch (RuntimeException failure) {
            super.close();
            throw failure;
        }
    }

    /** 创建注册表；生产环境推荐由 Flyway 或 Liquibase 执行迁移脚本。 */
    public void initializeSchema() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            BotRegistrationMapper registrations = session.getMapper(BotRegistrationMapper.class);
            registrations.createTable();
            try {
                registrations.addCurrentAttemptColumn();
            } catch (RuntimeException failure) {
                if (!isDuplicateColumn(failure)) {
                    throw failure;
                }
            }
            session.getMapper(BotLoginAttemptMapper.class).createTable();
            LOGGER.info("Bot 注册表结构初始化完成");
        } catch (RuntimeException failure) {
            LOGGER.error("Bot 注册表结构初始化失败", failure);
            throw new BotOperationException("初始化 Bot 注册表失败", failure);
        }
    }

    /**
     * 校验运行期依赖的注册表结构。
     *
     * <p>生产环境通常关闭自动建表，因此仍需在启动时快速失败，避免直到用户点击扫码或解绑时
     * 才暴露漏执行迁移的问题。
     */
    public void validateSchema(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "数据源不能为空");
        try (Connection connection = dataSource.getConnection()) {
            verifyQuery(connection,
                    "SELECT current_login_attempt_id FROM wxbot_bot_registry WHERE 1=0");
            verifyQuery(connection,
                    "SELECT attempt_id,user_id,phase,registration_status "
                            + "FROM wxbot_bot_login_attempt WHERE 1=0");
            LOGGER.info("Bot 注册表结构校验通过");
        } catch (SQLException failure) {
            LOGGER.error("Bot 注册表结构校验失败，请先执行数据库登录状态迁移", failure);
            throw new BotOperationException(
                    "Bot 数据库结构不完整，请先执行 db/mysql/V4__ensure_login_attempt_schema.sql",
                    failure);
        }
    }

    @Override
    public CompletionStage<BotRegistration> create(
            String userId, String clientKey, String displayName) {
        return async(() -> {
            Instant now = clock.instant();
            BotRegistration registration = new BotRegistration(
                    required(userId), Objects.requireNonNull(clientKey, "客户端隔离键不能为空"),
                    Objects.requireNonNull(displayName, "展示名称不能为空"),
                    BotStatus.LOGIN_REQUIRED, null, now, now, 0L);
            try (SqlSession session = sessionFactory.openSession(true)) {
                session.getMapper(BotRegistrationMapper.class).insert(toEntity(registration));
                LOGGER.info("创建 Bot 业务绑定成功，userId={}", registration.userId());
                return registration;
            } catch (RuntimeException failure) {
                if (isConstraintViolation(failure)) {
                    LOGGER.warn("拒绝重复创建 Bot 业务绑定，userId={}", registration.userId());
                    throw new BotConflictException("该用户已经绑定一个 Bot");
                }
                throw databaseFailure("创建 Bot 绑定失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Optional<BotRegistration>> find(String userId) {
        return async(() -> Optional.ofNullable(findEntity(required(userId)))
                .map(MybatisPlusBotRegistrationRepository::toModel));
    }

    @Override
    public CompletionStage<List<BotRegistration>> list() {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession()) {
                return session.getMapper(BotRegistrationMapper.class).selectAllOrdered()
                        .stream().map(MybatisPlusBotRegistrationRepository::toModel).toList();
            } catch (RuntimeException failure) {
                throw databaseFailure("列出 Bot 绑定失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<BotRegistration> updateStatus(
            String userId, BotStatus status, String lastError) {
        return async(() -> {
            String normalized = required(userId);
            try (SqlSession session = sessionFactory.openSession(true)) {
                int changed = session.getMapper(BotRegistrationMapper.class).updateStatus(
                        normalized, Objects.requireNonNull(status, "Bot 状态不能为空").name(),
                        sanitize(lastError), clock.instant().toEpochMilli());
                if (changed == 0) {
                    throw new BotNotFoundException("该用户尚未绑定 Bot");
                }
            } catch (BotNotFoundException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw databaseFailure("更新 Bot 状态失败", failure);
            }
            BotRegistration updated = requireEntity(normalized);
            LOGGER.info("Bot 状态已更新，userId={}，status={}，version={}",
                    normalized, updated.status(), updated.version());
            return updated;
        });
    }

    @Override
    public CompletionStage<Boolean> compareAndSetStatus(
            String userId, Set<BotStatus> expected, BotStatus status) {
        List<String> expectedNames = statusNames(expected);
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession(true)) {
                boolean changed = session.getMapper(BotRegistrationMapper.class).compareAndSetStatus(
                        required(userId), expectedNames,
                        Objects.requireNonNull(status, "Bot 状态不能为空").name(),
                        clock.instant().toEpochMilli()) == 1;
                if (changed) {
                    LOGGER.info("Bot 状态原子切换成功，userId={}，status={}", userId, status);
                }
                return changed;
            } catch (RuntimeException failure) {
                throw databaseFailure("抢占 Bot 状态失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> beginLogin(
            String userId, Set<BotStatus> expected, String attemptId, Instant expiresAt) {
        List<String> expectedNames = statusNames(expected);
        String normalizedUserId = required(userId);
        String normalizedAttemptId = requiredAttemptId(attemptId);
        Instant expiry = Objects.requireNonNull(expiresAt, "保护性失效时间不能为空");
        if (!expiry.isAfter(clock.instant())) {
            throw new IllegalArgumentException("保护性失效时间必须晚于当前时间");
        }
        return async(() -> inTransaction(session -> {
            long now = clock.instant().toEpochMilli();
            BotRegistrationMapper registrations = session.getMapper(BotRegistrationMapper.class);
            if (registrations.claimLogin(
                    normalizedUserId, expectedNames, normalizedAttemptId, now) != 1) {
                return false;
            }
            session.getMapper(BotLoginAttemptMapper.class).insert(newAttempt(
                    normalizedAttemptId, normalizedUserId, expiry, now));
            LOGGER.info("二维码登录尝试已抢占，userId={}，attemptId={}",
                    normalizedUserId, normalizedAttemptId);
            return true;
        }, "创建二维码登录尝试失败"));
    }

    @Override
    public CompletionStage<Boolean> updateLoginChallenge(
            String userId, String attemptId, Instant expiresAt) {
        Objects.requireNonNull(expiresAt, "二维码失效时间不能为空");
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession(true)) {
                return session.getMapper(BotLoginAttemptMapper.class).updateChallenge(
                        required(userId), requiredAttemptId(attemptId),
                        expiresAt.toEpochMilli(), clock.instant().toEpochMilli()) == 1;
            } catch (RuntimeException failure) {
                throw databaseFailure("更新二维码登录尝试失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Optional<BotLoginAttempt>> findLoginAttempt(
            String userId, String attemptId) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession()) {
                return Optional.ofNullable(session.getMapper(BotLoginAttemptMapper.class)
                                .selectForUser(required(userId), requiredAttemptId(attemptId)))
                        .map(MybatisPlusBotRegistrationRepository::toModel);
            } catch (RuntimeException failure) {
                throw databaseFailure("查询二维码登录尝试失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Optional<BotLoginAttempt>> findCurrentLoginAttempt(String userId) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession()) {
                return Optional.ofNullable(session.getMapper(BotLoginAttemptMapper.class)
                                .selectCurrent(required(userId)))
                        .map(MybatisPlusBotRegistrationRepository::toModel);
            } catch (RuntimeException failure) {
                throw databaseFailure("查询当前二维码登录尝试失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> updateLoginPhase(
            String userId, String attemptId, BotLoginPhase phase, String safeMessage) {
        List<String> previous = switch (Objects.requireNonNull(phase, "登录阶段不能为空")) {
            case SCANNED -> List.of(BotLoginPhase.WAITING_SCAN.name());
            case CONFIRMED -> List.of(BotLoginPhase.WAITING_SCAN.name(), BotLoginPhase.SCANNED.name());
            case BINDING -> List.of(BotLoginPhase.CONFIRMED.name());
            default -> throw new IllegalArgumentException("只能推进到已扫码、已确认或绑定中阶段");
        };
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession(true)) {
                boolean changed = session.getMapper(BotLoginAttemptMapper.class).updatePhase(
                        required(userId), requiredAttemptId(attemptId), phase.name(),
                        sanitize(safeMessage), previous, clock.instant().toEpochMilli()) == 1;
                if (changed) {
                    LOGGER.info("二维码登录阶段已推进，userId={}，attemptId={}，phase={}",
                            userId, attemptId, phase);
                }
                return changed;
            } catch (RuntimeException failure) {
                throw databaseFailure("更新二维码登录尝试失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> completeLogin(String userId, String attemptId) {
        return async(() -> finishLogin(userId, attemptId, BotLoginPhase.BOUND,
                BotStatus.ONLINE, "微信身份与业务用户绑定完成"));
    }

    @Override
    public CompletionStage<Boolean> failLogin(
            String userId, String attemptId, BotLoginPhase phase,
            BotStatus targetStatus, String safeMessage) {
        if (phase != BotLoginPhase.EXPIRED && phase != BotLoginPhase.FAILED) {
            throw new IllegalArgumentException("失败登录只能结束为过期或失败阶段");
        }
        if (targetStatus != BotStatus.LOGIN_REQUIRED && targetStatus != BotStatus.ERROR) {
            throw new IllegalArgumentException("失败登录的目标注册状态必须为 LOGIN_REQUIRED 或 ERROR");
        }
        return async(() -> finishLogin(userId, attemptId, phase, targetStatus, safeMessage));
    }

    @Override
    public CompletionStage<Void> delete(String userId) {
        String normalized = required(userId);
        return async(() -> inTransaction(session -> {
            session.getMapper(BotLoginAttemptMapper.class).deleteByUserId(normalized);
            session.getMapper(BotRegistrationMapper.class).deleteByUserId(normalized);
            LOGGER.info("Bot 业务绑定及登录历史已删除，userId={}", normalized);
            return null;
        }, "删除 Bot 绑定失败"));
    }

    private boolean finishLogin(
            String userId, String attemptId, BotLoginPhase phase,
            BotStatus targetStatus, String safeMessage) {
        String normalizedUserId = required(userId);
        String normalizedAttemptId = requiredAttemptId(attemptId);
        boolean invalidatingBound = phase == BotLoginPhase.FAILED
                && targetStatus == BotStatus.LOGIN_REQUIRED;
        List<String> previous = switch (phase) {
            case BOUND -> List.of(BotLoginPhase.BINDING.name());
            case EXPIRED -> List.of(BotLoginPhase.WAITING_SCAN.name(), BotLoginPhase.SCANNED.name());
            case FAILED -> invalidatingBound ? List.of(BotLoginPhase.BOUND.name()) : List.of(
                    BotLoginPhase.WAITING_SCAN.name(), BotLoginPhase.SCANNED.name(),
                    BotLoginPhase.CONFIRMED.name(), BotLoginPhase.BINDING.name());
            default -> throw new IllegalArgumentException("不支持的登录终态：" + phase);
        };
        return inTransaction(session -> {
            long now = clock.instant().toEpochMilli();
            String expectedStatus = invalidatingBound
                    ? BotStatus.ONLINE.name() : BotStatus.LOGIN_PENDING.name();
            int attemptChanged = session.getMapper(BotLoginAttemptMapper.class).finish(
                    normalizedUserId, normalizedAttemptId, phase.name(), sanitize(safeMessage),
                    targetStatus.name(), previous, expectedStatus, now);
            if (attemptChanged != 1) {
                return false;
            }
            int registrationChanged = session.getMapper(BotRegistrationMapper.class).finishLogin(
                    normalizedUserId, normalizedAttemptId, expectedStatus, targetStatus.name(),
                    targetStatus == BotStatus.ERROR ? sanitize(safeMessage) : null, now);
            if (registrationChanged != 1) {
                throw new BotOperationException("当前登录尝试对应的业务绑定状态已经变化");
            }
            LOGGER.info("二维码登录尝试已结束，userId={}，attemptId={}，phase={}，status={}",
                    normalizedUserId, normalizedAttemptId, phase, targetStatus);
            return true;
        }, "更新二维码登录终态失败");
    }

    private BotRegistrationEntity findEntity(String userId) {
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(BotRegistrationMapper.class).selectById(userId);
        } catch (RuntimeException failure) {
            throw databaseFailure("查询 Bot 绑定失败", failure);
        }
    }

    private BotRegistration requireEntity(String userId) {
        BotRegistrationEntity entity = findEntity(userId);
        if (entity == null) {
            throw new BotNotFoundException("该用户尚未绑定 Bot");
        }
        return toModel(entity);
    }

    private <T> T inTransaction(TransactionAction<T> action, String safeMessage) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                T result = action.execute(session);
                session.commit();
                return result;
            } catch (Throwable failure) {
                session.rollback();
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new BotOperationException(safeMessage, failure);
            }
        } catch (RuntimeException failure) {
            if (failure instanceof BotOperationException) {
                throw failure;
            }
            throw databaseFailure(safeMessage, failure);
        }
    }

    private <T> CompletionStage<T> async(Supplier<T> action) {
        return submit(action,
                failure -> new BotOperationException("Bot 注册表任务队列已满", failure));
    }

    private static BotRegistrationEntity toEntity(BotRegistration model) {
        BotRegistrationEntity entity = new BotRegistrationEntity();
        entity.setUserId(model.userId());
        entity.setClientKey(model.clientKey());
        entity.setDisplayName(model.displayName());
        entity.setStatus(model.status().name());
        entity.setLastError(model.lastError());
        entity.setCreatedAt(model.createdAt().toEpochMilli());
        entity.setUpdatedAt(model.updatedAt().toEpochMilli());
        entity.setVersion(model.version());
        return entity;
    }

    private static BotRegistration toModel(BotRegistrationEntity entity) {
        return new BotRegistration(entity.getUserId(), entity.getClientKey(), entity.getDisplayName(),
                BotStatus.valueOf(entity.getStatus()), entity.getLastError(),
                Instant.ofEpochMilli(entity.getCreatedAt()), Instant.ofEpochMilli(entity.getUpdatedAt()),
                entity.getVersion());
    }

    private static BotLoginAttemptEntity newAttempt(
            String attemptId, String userId, Instant expiresAt, long now) {
        BotLoginAttemptEntity entity = new BotLoginAttemptEntity();
        entity.setAttemptId(attemptId);
        entity.setUserId(userId);
        entity.setPhase(BotLoginPhase.WAITING_SCAN.name());
        entity.setMessage("二维码生成中");
        entity.setRegistrationStatus(BotStatus.LOGIN_PENDING.name());
        entity.setExpiresAt(expiresAt.toEpochMilli());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setVersion(0L);
        return entity;
    }

    private static BotLoginAttempt toModel(BotLoginAttemptEntity entity) {
        return new BotLoginAttempt(entity.getAttemptId(), entity.getUserId(),
                BotLoginPhase.valueOf(entity.getPhase()), entity.getMessage(),
                BotStatus.valueOf(entity.getRegistrationStatus()),
                Instant.ofEpochMilli(entity.getExpiresAt()),
                Instant.ofEpochMilli(entity.getCreatedAt()),
                Instant.ofEpochMilli(entity.getUpdatedAt()), entity.getVersion());
    }

    private static List<String> statusNames(Set<BotStatus> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("允许的原状态不能为空");
        }
        List<String> names = new ArrayList<>();
        Set.copyOf(values).forEach(value -> names.add(value.name()));
        return List.copyOf(names);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户唯一标识不能为空");
        }
        return value;
    }

    private static String requiredAttemptId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("登录尝试标识不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("登录尝试标识长度不能超过 64");
        }
        return normalized;
    }

    private static boolean isConstraintViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlFailure
                    && sqlFailure.getSQLState() != null
                    && sqlFailure.getSQLState().startsWith("23")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isDuplicateColumn(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Duplicate column")
                    || message.contains("already exists") || message.contains("42121"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static BotOperationException databaseFailure(String message, Throwable cause) {
        LOGGER.error("{}", message, cause);
        return new BotOperationException(message, cause);
    }

    private static void verifyQuery(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeQuery();
        }
    }

    /** 停止接受新的注册表任务。 */
    @Override
    public void close() {
        super.close();
        LOGGER.info("Bot 注册表已关闭");
    }

    @FunctionalInterface
    private interface TransactionAction<T> {
        T execute(SqlSession session) throws Exception;
    }
}
