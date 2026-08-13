/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.store.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wxbot.ilink.api.message.InboxStore;
import io.github.wxbot.ilink.api.message.FencedInboxStore;
import io.github.wxbot.ilink.api.message.DeadLetterMessage;
import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.MessageItem;
import io.github.wxbot.ilink.api.message.PersistedBatch;
import io.github.wxbot.ilink.api.message.StoredMessage;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;
import io.github.wxbot.ilink.api.session.LeaseStore;
import io.github.wxbot.ilink.api.session.StateStore;
import io.github.wxbot.ilink.store.mybatis.entity.CursorEntity;
import io.github.wxbot.ilink.store.mybatis.entity.InboxEntity;
import io.github.wxbot.ilink.store.mybatis.entity.LeaseEntity;
import io.github.wxbot.ilink.store.mybatis.entity.SnapshotEntity;
import io.github.wxbot.ilink.store.mybatis.mapper.CursorMapper;
import io.github.wxbot.ilink.store.mybatis.mapper.InboxMapper;
import io.github.wxbot.ilink.store.mybatis.mapper.LeaseMapper;
import io.github.wxbot.ilink.store.mybatis.mapper.SnapshotMapper;
import io.github.wxbot.ilink.store.mybatis.support.MybatisPlusSessionFactory;
import io.github.wxbot.ilink.store.mybatis.support.AbstractBoundedAsyncRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 同时实现快照与可靠收件箱的 JDBC 存储。
 *
 * <p>消息写入、去重和 cursor 推进位于同一数据库事务。快照、上下文令牌及完整消息使用 AES-GCM 加密后
 * 保存，数据库中不会出现 token 原文。阻塞 JDBC 调用运行在独立有界线程池中，不占用网络回调线程。
 * 当前建表 SQL 已在 H2 验证，并仅使用常见 JDBC 类型，接入其他数据库时建议由应用迁移工具预建同结构表。
 */
public final class JdbcILinkStore extends AbstractBoundedAsyncRepository
        implements StateStore, FencedInboxStore {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcILinkStore.class);
    private final DataSource dataSource;
    private final SqlSessionFactory sessionFactory;
    private final byte[] masterKey;

    /** 使用 2 个存储线程和 256 个排队任务创建存储并初始化表。 */
    public JdbcILinkStore(DataSource dataSource, byte[] masterKey) {
        this(dataSource, masterKey, 2, 256, true);
    }

    /** 创建 JDBC 存储。 */
    public JdbcILinkStore(
            DataSource dataSource, byte[] masterKey, int workerCount, int queueCapacity,
            boolean initializeSchema) {
        super("wxbot-ilink-jdbc-store", workerCount, queueCapacity);
        this.dataSource = Objects.requireNonNull(dataSource, "数据源不能为空");
        this.sessionFactory = MybatisPlusSessionFactory.create(dataSource,
                SnapshotMapper.class, CursorMapper.class, InboxMapper.class, LeaseMapper.class);
        if (masterKey == null || (masterKey.length != 16 && masterKey.length != 24
                && masterKey.length != 32)) {
            throw new IllegalArgumentException("存储主密钥必须是 16、24 或 32 字节");
        }
        this.masterKey = masterKey.clone();
        if (initializeSchema) {
            initializeSchema();
        }
    }

    /** 创建存储所需表；生产环境也可通过数据库迁移工具预建。 */
    public void initializeSchema() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS wxbot_ilink_snapshot ("
                    + "client_key VARCHAR(255) PRIMARY KEY, payload BLOB NOT NULL, saved_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS wxbot_ilink_cursor ("
                    + "client_key VARCHAR(255) PRIMARY KEY, cursor_value VARCHAR(4096) NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS wxbot_ilink_inbox ("
                    + "client_key VARCHAR(255) NOT NULL, message_id BIGINT NOT NULL, payload BLOB NOT NULL, "
                    + "created_at BIGINT NOT NULL, attempt INT NOT NULL, available_at BIGINT NOT NULL, "
                    + "claimed_until BIGINT, acknowledged BOOLEAN NOT NULL, dead_letter BOOLEAN NOT NULL, "
                    + "failed_at BIGINT, last_error VARCHAR(255), "
                    + "PRIMARY KEY (client_key, message_id))");
            addColumnIfMissing(connection, statement, "wxbot_ilink_inbox", "dead_letter",
                    "ALTER TABLE wxbot_ilink_inbox ADD COLUMN dead_letter BOOLEAN DEFAULT FALSE NOT NULL");
            addColumnIfMissing(connection, statement, "wxbot_ilink_inbox", "failed_at",
                    "ALTER TABLE wxbot_ilink_inbox ADD COLUMN failed_at BIGINT");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS wxbot_ilink_inbox_pending "
                    + "ON wxbot_ilink_inbox(client_key, acknowledged, available_at, created_at)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS wxbot_ilink_lease ("
                    + "client_key VARCHAR(255) PRIMARY KEY, owner_id VARCHAR(255) NOT NULL, "
                    + "expires_at BIGINT NOT NULL)");
            LOGGER.info("iLink MyBatis-Plus 存储结构初始化完成");
        } catch (SQLException failure) {
            LOGGER.error("iLink 存储结构初始化失败", failure);
            throw new IllegalStateException("初始化 JDBC 存储结构失败", failure);
        }
    }

    private static void addColumnIfMissing(
            Connection connection,
            Statement statement,
            String table,
            String column,
            String sql) throws SQLException {
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, null, null)) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    return;
                }
            }
        }
        statement.executeUpdate(sql);
    }

    @Override
    public CompletionStage<Optional<ClientSnapshot>> load(String clientKey) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession()) {
                SnapshotEntity entity = session.getMapper(SnapshotMapper.class)
                        .selectById(required(clientKey));
                return entity == null ? Optional.empty()
                        : Optional.of(decodeSnapshot(decrypt(entity.getPayload())));
            } catch (RuntimeException failure) {
                throw storeFailure("加载客户端快照失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Void> save(String clientKey, ClientSnapshot snapshot) {
        return async(() -> {
            byte[] payload = encrypt(encodeSnapshot(Objects.requireNonNull(snapshot, "快照不能为空")));
            String key = required(clientKey);
            try (SqlSession session = sessionFactory.openSession(false)) {
                SnapshotMapper mapper = session.getMapper(SnapshotMapper.class);
                long savedAt = snapshot.savedAt().toEpochMilli();
                if (mapper.updateSnapshot(key, payload, savedAt) == 0) {
                    SnapshotEntity entity = new SnapshotEntity();
                    entity.setClientKey(key);
                    entity.setPayload(payload);
                    entity.setSavedAt(savedAt);
                    mapper.insert(entity);
                }
                session.commit();
                LOGGER.debug("客户端加密快照已保存，clientKey={}", safeKey(key));
                return null;
            } catch (RuntimeException failure) {
                throw storeFailure("保存客户端快照失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Void> clear(String clientKey) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession(true)) {
                session.getMapper(SnapshotMapper.class).deleteById(required(clientKey));
                return null;
            } catch (RuntimeException failure) {
                throw storeFailure("清除客户端快照失败", failure);
            }
        });
    }

    /**
     * 清除一个客户端的全部持久化数据。
     *
     * <p>该操作在单一事务内删除快照、游标、收件箱和租约，供管理后台删除 Bot 时使用。调用方必须先停止
     * 对应客户端，避免删除后又被仍在运行的拉取任务写回。
     *
     * @param clientKey 客户端唯一键
     * @return 清理完成阶段
     */
    public CompletionStage<Void> purgeClient(String clientKey) {
        return async(() -> inMapperTransaction(session -> {
            String key = required(clientKey);
            session.getMapper(InboxMapper.class).deleteByClientKey(key);
            session.getMapper(CursorMapper.class).deleteByClientKey(key);
            session.getMapper(SnapshotMapper.class).deleteByClientKey(key);
            session.getMapper(LeaseMapper.class).deleteByClientKey(key);
            LOGGER.info("客户端持久化数据已清理，clientKey={}", safeKey(key));
            return null;
        }));
    }

    @Override
    public CompletionStage<String> loadCursor(String clientKey) {
        return async(() -> inMapperTransaction(session -> {
            String key = required(clientKey);
            CursorMapper mapper = session.getMapper(CursorMapper.class);
            ensureCursor(mapper, key);
            CursorEntity cursor = mapper.selectById(key);
            return cursor == null ? "" : cursor.getCursorValue();
        }));
    }

    @Override
    public CompletionStage<PersistedBatch> persistBatch(
            String clientKey, String expectedCursor, UpdateBatch batch, Duration claimTimeout) {
        return persistBatch(clientKey, null, null, expectedCursor, batch, claimTimeout);
    }

    @Override
    public CompletionStage<PersistedBatch> persistBatchWhileLeaseHeld(
            String clientKey,
            String ownerId,
            Instant now,
            String expectedCursor,
            UpdateBatch batch,
            Duration claimTimeout) {
        requiredOwner(ownerId);
        Objects.requireNonNull(now, "租约校验时间不能为空");
        return persistBatch(clientKey, ownerId, now, expectedCursor, batch, claimTimeout);
    }

    private CompletionStage<PersistedBatch> persistBatch(
            String clientKey,
            String ownerId,
            Instant leaseCheckTime,
            String expectedCursor,
            UpdateBatch batch,
            Duration claimTimeout) {
        return async(() -> inMapperTransaction(session -> {
            validateClaimTimeout(claimTimeout);
            String key = required(clientKey);
            if (ownerId != null) {
                requireActiveLease(session.getMapper(LeaseMapper.class), key, ownerId, leaseCheckTime);
            }
            CursorMapper cursorMapper = session.getMapper(CursorMapper.class);
            InboxMapper inboxMapper = session.getMapper(InboxMapper.class);
            ensureCursor(cursorMapper, key);
            CursorEntity lockedCursor = cursorMapper.selectForUpdate(key);
            String current = lockedCursor.getCursorValue();
            String expected = expectedCursor == null ? "" : expectedCursor;
            if (!current.equals(expected)) {
                throw new IllegalStateException("消息游标已经被其他实例推进");
            }
            long now = Instant.now().toEpochMilli();
            long claimedUntil = Math.addExact(now, claimTimeout.toMillis());
            List<StoredMessage> accepted = new ArrayList<>();
            for (InboundMessage message : batch.messages()) {
                InboxEntity entity = new InboxEntity();
                entity.setClientKey(key);
                entity.setMessageId(message.messageId());
                entity.setPayload(encrypt(encodeMessage(message)));
                entity.setCreatedAt(message.createdAt().toEpochMilli());
                entity.setAttempt(1);
                entity.setAvailableAt(now);
                entity.setClaimedUntil(claimedUntil);
                entity.setAcknowledged(false);
                entity.setDeadLetter(false);
                try {
                    inboxMapper.insertMessage(entity);
                    accepted.add(new StoredMessage(key, message, 1, Instant.ofEpochMilli(now)));
                } catch (RuntimeException duplicate) {
                    if (!isConstraintViolation(duplicate)) {
                        throw duplicate;
                    }
                }
            }
            cursorMapper.updateCursor(key, batch.nextCursor());
            LOGGER.debug("消息批次已持久化，clientKey={}，接收数={}，新增数={}",
                    safeKey(key), batch.messages().size(), accepted.size());
            return new PersistedBatch(batch.nextCursor(), accepted);
        }));
    }

    private static void requireActiveLease(
            LeaseMapper mapper, String clientKey, String ownerId, Instant now) {
        LeaseEntity lease = mapper.selectForUpdate(clientKey);
        if (lease == null || !ownerId.equals(lease.getOwnerId())
                || lease.getExpiresAt() <= now.toEpochMilli()) {
            throw new IllegalStateException("客户端运行租约已经失效");
        }
    }

    @Override
    public CompletionStage<List<StoredMessage>> claimPending(
            String clientKey, Instant now, int limit, Duration claimTimeout) {
        return async(() -> inMapperTransaction(session -> {
            if (limit <= 0) {
                throw new IllegalArgumentException("读取数量必须大于零");
            }
            validateClaimTimeout(claimTimeout);
            String key = required(clientKey);
            InboxMapper mapper = session.getMapper(InboxMapper.class);
            List<StoredMessage> messages = new ArrayList<>();
            List<InboxEntity> entities = mapper.selectClaimableForUpdate(
                    key, now.toEpochMilli(), limit);
            for (InboxEntity entity : entities) {
                messages.add(new StoredMessage(key, decryptMessage(entity.getPayload()),
                        entity.getAttempt(), Instant.ofEpochMilli(entity.getAvailableAt())));
            }
            long until = now.plus(claimTimeout).toEpochMilli();
            for (InboxEntity entity : entities) {
                mapper.claim(key, entity.getMessageId(), until);
            }
            return messages;
        }));
    }

    @Override
    public CompletionStage<Void> acknowledge(String clientKey, long messageId) {
        return updateMessage(clientKey, mapper -> mapper.acknowledge(clientKey, messageId));
    }

    @Override
    public CompletionStage<Void> release(String clientKey, long messageId) {
        return updateMessage(clientKey, mapper -> mapper.release(clientKey, messageId));
    }

    @Override
    public CompletionStage<Void> markForRetry(
            String clientKey, long messageId, Duration delay, String reason) {
        if (delay == null || delay.isNegative()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("重试延迟不能为负数"));
        }
        long availableAt = Instant.now().plus(delay).toEpochMilli();
        return updateMessage(clientKey, mapper -> mapper.markForRetry(
                clientKey, messageId, availableAt, sanitize(reason)));
    }

    @Override
    public CompletionStage<Void> deadLetter(
            String clientKey, long messageId, String reason, Instant failedAt) {
        Objects.requireNonNull(failedAt, "死信时间不能为空");
        return updateMessage(clientKey, mapper -> mapper.deadLetter(
                clientKey, messageId, sanitize(reason), failedAt.toEpochMilli()));
    }

    @Override
    public CompletionStage<List<DeadLetterMessage>> loadDeadLetters(String clientKey, int limit) {
        if (limit <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("读取数量必须大于零"));
        }
        return async(() -> {
            List<DeadLetterMessage> messages = new ArrayList<>();
            try (SqlSession session = sessionFactory.openSession()) {
                for (InboxEntity entity : session.getMapper(InboxMapper.class)
                        .selectDeadLetters(required(clientKey), limit)) {
                    messages.add(new DeadLetterMessage(
                            decryptMessage(entity.getPayload()), entity.getAttempt(),
                            entity.getLastError(), Instant.ofEpochMilli(entity.getFailedAt())));
                }
                return messages;
            } catch (RuntimeException failure) {
                throw storeFailure("加载死信消息失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Long> countPending(String clientKey) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession()) {
                return session.getMapper(InboxMapper.class).countPending(required(clientKey));
            } catch (RuntimeException failure) {
                throw storeFailure("统计收件箱积压失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> tryAcquire(
            String clientKey, String ownerId, Instant now, Duration ttl) {
        return async(() -> inMapperTransaction(session -> {
            validateLease(clientKey, ownerId, now, ttl);
            LeaseMapper mapper = session.getMapper(LeaseMapper.class);
            LeaseEntity current = mapper.selectForUpdate(clientKey);
            long expiry = now.plus(ttl).toEpochMilli();
            if (current != null) {
                if (!current.getOwnerId().equals(ownerId)
                        && current.getExpiresAt() > now.toEpochMilli()) {
                    return false;
                }
                mapper.takeOver(clientKey, ownerId, expiry);
                LOGGER.info("客户端运行租约已接管，clientKey={}，ownerId={}",
                        safeKey(clientKey), ownerId);
                return true;
            }
            LeaseEntity created = new LeaseEntity();
            created.setClientKey(clientKey);
            created.setOwnerId(ownerId);
            created.setExpiresAt(expiry);
            try {
                mapper.insert(created);
                LOGGER.info("客户端运行租约已获取，clientKey={}，ownerId={}",
                        safeKey(clientKey), ownerId);
                return true;
            } catch (RuntimeException duplicate) {
                if (isConstraintViolation(duplicate)) {
                    return false;
                }
                throw duplicate;
            }
        }));
    }

    @Override
    public CompletionStage<Boolean> renew(
            String clientKey, String ownerId, Instant now, Duration ttl) {
        return async(() -> {
            validateLease(clientKey, ownerId, now, ttl);
            try (SqlSession session = sessionFactory.openSession(true)) {
                boolean renewed = session.getMapper(LeaseMapper.class).renew(
                        clientKey, ownerId, now.toEpochMilli(), now.plus(ttl).toEpochMilli()) == 1;
                if (!renewed) {
                    LOGGER.warn("客户端运行租约续订失败，clientKey={}，ownerId={}",
                            safeKey(clientKey), ownerId);
                }
                return renewed;
            } catch (RuntimeException failure) {
                throw storeFailure("续订客户端租约失败", failure);
            }
        });
    }

    @Override
    public CompletionStage<Void> release(String clientKey, String ownerId) {
        return async(() -> {
            try (SqlSession session = sessionFactory.openSession(true)) {
                session.getMapper(LeaseMapper.class).release(
                        required(clientKey), requiredOwner(ownerId));
                LOGGER.info("客户端运行租约已释放，clientKey={}，ownerId={}",
                        safeKey(clientKey), ownerId);
                return null;
            } catch (RuntimeException failure) {
                throw storeFailure("释放客户端租约失败", failure);
            }
        });
    }

    private CompletionStage<Void> updateMessage(String clientKey, MessageUpdate operation) {
        return async(() -> {
            required(clientKey);
            try (SqlSession session = sessionFactory.openSession(true)) {
                operation.apply(session.getMapper(InboxMapper.class));
                return null;
            } catch (RuntimeException failure) {
                throw storeFailure("更新收件箱消息状态失败", failure);
            }
        });
    }

    private static void ensureCursor(CursorMapper mapper, String clientKey) {
        if (mapper.selectById(clientKey) != null) {
            return;
        }
        CursorEntity cursor = new CursorEntity();
        cursor.setClientKey(clientKey);
        cursor.setCursorValue("");
        try {
            mapper.insert(cursor);
        } catch (RuntimeException duplicate) {
            if (!isConstraintViolation(duplicate)) {
                throw duplicate;
            }
        }
    }

    private <T> T inMapperTransaction(MapperOperation<T> operation) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            try {
                T value = operation.apply(session);
                session.commit();
                return value;
            } catch (Throwable failure) {
                session.rollback();
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw storeFailure("MyBatis-Plus 事务失败", failure);
            }
        }
    }

    private byte[] encodeSnapshot(ClientSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", snapshot.schemaVersion());
        value.put("botToken", snapshot.session().botToken());
        value.put("userId", snapshot.session().userId());
        value.put("botId", snapshot.session().botId());
        value.put("baseUri", snapshot.session().baseUri().toString());
        value.put("cursor", snapshot.cursor());
        value.put("savedAt", snapshot.savedAt().toEpochMilli());
        value.put("conversations", snapshot.conversations().values().stream().map(item -> Map.of(
                "userId", item.userId(), "contextToken", item.contextToken(),
                "sourceMessageId", item.sourceMessageId(),
                "sourceMessageTime", item.sourceMessageTime().toEpochMilli(),
                "updatedAt", item.updatedAt().toEpochMilli())).toList());
        return jsonBytes(value);
    }

    private ClientSnapshot decodeSnapshot(byte[] bytes) {
        try {
            JsonNode value = JSON.readTree(bytes);
            Map<String, ConversationSnapshot> conversations = new LinkedHashMap<>();
            for (JsonNode item : value.path("conversations")) {
                ConversationSnapshot snapshot = new ConversationSnapshot(
                        item.path("userId").asText(), item.path("contextToken").asText(),
                        item.path("sourceMessageId").asLong(),
                        Instant.ofEpochMilli(item.path("sourceMessageTime").asLong()),
                        Instant.ofEpochMilli(item.path("updatedAt").asLong()));
                conversations.put(snapshot.userId(), snapshot);
            }
            return new ClientSnapshot(value.path("schemaVersion").asInt(), new BotSession(
                    value.path("botToken").asText(), value.path("userId").asText(),
                    value.path("botId").asText(), URI.create(value.path("baseUri").asText())),
                    value.path("cursor").asText(), conversations,
                    Instant.ofEpochMilli(value.path("savedAt").asLong()));
        } catch (Exception failure) {
            throw storeFailure("解析客户端快照失败", failure);
        }
    }

    private byte[] encodeMessage(InboundMessage message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("messageId", message.messageId());
        value.put("fromUserId", message.fromUserId());
        value.put("toUserId", message.toUserId());
        value.put("createdAt", message.createdAt().toEpochMilli());
        value.put("contextToken", message.contextToken());
        value.put("items", message.items().stream().map(item ->
                Map.of("type", item.type(), "attributes", item.attributes())).toList());
        return jsonBytes(value);
    }

    private InboundMessage decryptMessage(byte[] payload) {
        try {
            JsonNode value = JSON.readTree(decrypt(payload));
            List<MessageItem> items = new ArrayList<>();
            for (JsonNode item : value.path("items")) {
                Map<String, Object> attributes = JSON.convertValue(
                        item.path("attributes"), new TypeReference<Map<String, Object>>() { });
                items.add(new MessageItem(item.path("type").asInt(), attributes));
            }
            return new InboundMessage(value.path("messageId").asLong(),
                    value.path("fromUserId").asText(), value.path("toUserId").asText(),
                    Instant.ofEpochMilli(value.path("createdAt").asLong()),
                    value.path("contextToken").asText(), items);
        } catch (Exception failure) {
            throw storeFailure("解析收件箱消息失败", failure);
        }
    }

    private static byte[] jsonBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception failure) {
            throw storeFailure("序列化存储数据失败", failure);
        }
    }

    private byte[] encrypt(byte[] plain) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] result = new byte[1 + nonce.length + encrypted.length];
            result[0] = 1;
            System.arraycopy(nonce, 0, result, 1, nonce.length);
            System.arraycopy(encrypted, 0, result, 1 + nonce.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException failure) {
            throw storeFailure("加密持久化数据失败", failure);
        }
    }

    private byte[] decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length < 29 || encrypted[0] != 1) {
            throw storeFailure("持久化密文格式不受支持", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(128, encrypted, 1, 12));
            return cipher.doFinal(encrypted, 13, encrypted.length - 13);
        } catch (GeneralSecurityException failure) {
            throw storeFailure("解密持久化数据失败", failure);
        }
    }

    private <T> CompletionStage<T> async(Supplier<T> supplier) {
        return submit(supplier, failure -> storeFailure("持久化任务队列已满", failure));
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

    private static void validateClaimTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("领取超时必须大于零");
        }
    }

    private static void validateLease(
            String clientKey, String ownerId, Instant now, Duration ttl) {
        required(clientKey);
        requiredOwner(ownerId);
        Objects.requireNonNull(now, "租约时间不能为空");
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("租约期限必须大于零");
        }
    }

    private static String requiredOwner(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("租约所有者不能为空");
        }
        return value;
    }

    private static String sanitize(String reason) {
        if (reason == null) {
            return "未知失败";
        }
        return reason.length() > 255 ? reason.substring(0, 255) : reason;
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        return value;
    }

    private static IllegalStateException storeFailure(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    @Override
    public void close() {
        super.close();
        LOGGER.info("iLink 持久化工作线程已关闭");
    }

    @FunctionalInterface
    private interface MapperOperation<T> {
        T apply(SqlSession session) throws Exception;
    }

    @FunctionalInterface
    private interface MessageUpdate {
        int apply(InboxMapper mapper);
    }

    /** 日志只展示隔离键尾部，避免完整业务映射进入日志。 */
    private static String safeKey(String clientKey) {
        if (clientKey == null || clientKey.length() <= 8) {
            return "***";
        }
        return "***" + clientKey.substring(clientKey.length() - 8);
    }
}
