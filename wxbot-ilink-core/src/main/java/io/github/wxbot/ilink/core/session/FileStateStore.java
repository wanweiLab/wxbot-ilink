/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.session;

import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.session.ConversationSnapshot;
import io.github.wxbot.ilink.api.session.StateStore;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 面向单机部署的加密文件状态存储。
 *
 * <p>每个客户端使用独立的 AES-GCM 密文文件。保存过程先在同目录写临时文件并强制落盘，再通过原子移动
 * 替换旧快照；进程内互斥锁与操作系统文件锁共同避免多实例并发覆盖。阻塞文件操作在独立有界线程池执行，
 * 不占用网络回调线程。若文件系统不支持原子移动，保存会失败并保留原快照。
 */
public final class FileStateStore implements StateStore, AutoCloseable {

    private static final int FILE_MAGIC = 0x57494C4B;
    private static final int PAYLOAD_MAGIC = 0x5758534E;
    private static final byte FILE_VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int MAX_STRING_BYTES = 1024 * 1024;
    private static final int MAX_CONVERSATIONS = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;
    private final byte[] masterKey;
    private final ThreadPoolExecutor worker;

    /** 使用单个文件线程和 128 个排队任务创建存储。 */
    public FileStateStore(Path directory, byte[] masterKey) {
        this(directory, masterKey, 128);
    }

    /**
     * 创建文件状态存储。
     *
     * @param directory 快照目录
     * @param masterKey 16、24 或 32 字节 AES 主密钥
     * @param queueCapacity 文件任务队列容量
     */
    public FileStateStore(Path directory, byte[] masterKey, int queueCapacity) {
        this.directory = Objects.requireNonNull(directory, "快照目录不能为空")
                .toAbsolutePath().normalize();
        if (masterKey == null || (masterKey.length != 16 && masterKey.length != 24
                && masterKey.length != 32)) {
            throw new IllegalArgumentException("文件存储主密钥必须是 16、24 或 32 字节");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("文件任务队列容量必须大于零");
        }
        this.masterKey = masterKey.clone();
        this.worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "wxbot-ilink-file-store");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public CompletionStage<Optional<ClientSnapshot>> load(String clientKey) {
        String key = requiredKey(clientKey);
        return async(() -> withLock(key, () -> {
            Path snapshot = snapshotPath(key);
            if (!Files.exists(snapshot)) {
                return Optional.empty();
            }
            try {
                return Optional.of(decode(decrypt(Files.readAllBytes(snapshot), key)));
            } catch (IOException failure) {
                throw failure("读取文件快照失败", failure);
            }
        }));
    }

    @Override
    public CompletionStage<Void> save(String clientKey, ClientSnapshot snapshot) {
        String key = requiredKey(clientKey);
        Objects.requireNonNull(snapshot, "客户端快照不能为空");
        return async(() -> withLock(key, () -> {
            ensureDirectory();
            byte[] encrypted = encrypt(encode(snapshot), key);
            Path temporary;
            try {
                temporary = Files.createTempFile(directory, fileName(key) + ".", ".tmp");
                restrictPermissions(temporary);
            } catch (IOException failure) {
                throw failure("创建快照临时文件失败", failure);
            }
            boolean moved = false;
            try {
                try (FileChannel channel = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(encrypted);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                try {
                    Files.move(temporary, snapshotPath(key), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException failure) {
                    throw failure("当前文件系统不支持快照原子替换", failure);
                }
                moved = true;
                restrictPermissions(snapshotPath(key));
                forceDirectory();
                return null;
            } catch (IOException failure) {
                throw failure("原子保存文件快照失败", failure);
            } finally {
                if (!moved) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                        // 临时文件清理失败不覆盖原始保存异常。
                    }
                }
            }
        }));
    }

    @Override
    public CompletionStage<Void> clear(String clientKey) {
        String key = requiredKey(clientKey);
        return async(() -> withLock(key, () -> {
            try {
                Files.deleteIfExists(snapshotPath(key));
                forceDirectory();
                return null;
            } catch (IOException failure) {
                throw failure("清除文件快照失败", failure);
            }
        }));
    }

    private <T> T withLock(String clientKey, Supplier<T> operation) {
        try {
            ensureDirectory();
            Path lockPath = directory.resolve(fileName(clientKey) + ".lock");
            ReentrantLock processLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
            processLock.lockInterruptibly();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                restrictPermissions(lockPath);
                return operation.get();
            } finally {
                processLock.unlock();
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure("等待文件快照锁时被中断", failure);
        } catch (IOException failure) {
            throw failure("获取文件快照锁失败", failure);
        }
    }

    private void ensureDirectory() {
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw failure("创建快照目录失败", failure);
        }
    }

    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 部分平台不允许打开目录句柄；文件本身已完成强制落盘和原子替换。
        }
    }

    private byte[] encrypt(byte[] plain, String clientKey) {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(clientKey.getBytes(StandardCharsets.UTF_8));
            byte[] cipherText = cipher.doFinal(plain);
            ByteBuffer result = ByteBuffer.allocate(Integer.BYTES + 1 + NONCE_LENGTH + cipherText.length);
            result.putInt(FILE_MAGIC).put(FILE_VERSION).put(nonce).put(cipherText);
            return result.array();
        } catch (GeneralSecurityException failure) {
            throw failure("加密文件快照失败", failure);
        }
    }

    private byte[] decrypt(byte[] encrypted, String clientKey) {
        if (encrypted == null || encrypted.length < Integer.BYTES + 1 + NONCE_LENGTH + 16) {
            throw failure("文件快照密文长度无效", null);
        }
        ByteBuffer source = ByteBuffer.wrap(encrypted);
        if (source.getInt() != FILE_MAGIC || source.get() != FILE_VERSION) {
            throw failure("文件快照格式或版本不受支持", null);
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        source.get(nonce);
        byte[] cipherText = new byte[source.remaining()];
        source.get(cipherText);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(clientKey.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(cipherText);
        } catch (AEADBadTagException failure) {
            throw failure("文件快照认证失败，密钥错误或文件已损坏", failure);
        } catch (GeneralSecurityException failure) {
            throw failure("解密文件快照失败", failure);
        }
    }

    private static byte[] encode(ClientSnapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(PAYLOAD_MAGIC);
                output.writeInt(snapshot.schemaVersion());
                writeString(output, snapshot.session().botToken());
                writeString(output, snapshot.session().userId());
                writeString(output, snapshot.session().botId());
                writeString(output, snapshot.session().baseUri().toString());
                writeString(output, snapshot.cursor());
                output.writeLong(snapshot.savedAt().toEpochMilli());
                output.writeInt(snapshot.conversations().size());
                for (ConversationSnapshot item : snapshot.conversations().values()) {
                    writeString(output, item.userId());
                    writeString(output, item.contextToken());
                    output.writeLong(item.sourceMessageId());
                    output.writeLong(item.sourceMessageTime().toEpochMilli());
                    output.writeLong(item.updatedAt().toEpochMilli());
                }
            }
            return bytes.toByteArray();
        } catch (IOException failure) {
            throw failure("序列化文件快照失败", failure);
        }
    }

    private static ClientSnapshot decode(byte[] payload) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != PAYLOAD_MAGIC) {
                throw failure("文件快照载荷格式无效", null);
            }
            int schemaVersion = input.readInt();
            BotSession session = new BotSession(readString(input), readString(input), readString(input),
                    URI.create(readString(input)));
            String cursor = readString(input);
            Instant savedAt = Instant.ofEpochMilli(input.readLong());
            int count = input.readInt();
            if (count < 0 || count > MAX_CONVERSATIONS) {
                throw failure("文件快照会话数量无效", null);
            }
            Map<String, ConversationSnapshot> conversations = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                ConversationSnapshot conversation = new ConversationSnapshot(
                        readString(input), readString(input), input.readLong(),
                        Instant.ofEpochMilli(input.readLong()), Instant.ofEpochMilli(input.readLong()));
                conversations.put(conversation.userId(), conversation);
            }
            if (input.available() != 0) {
                throw failure("文件快照包含未识别数据", null);
            }
            return new ClientSnapshot(schemaVersion, session, cursor, conversations, savedAt);
        } catch (EOFException failure) {
            throw failure("文件快照载荷不完整", failure);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof IllegalStateException state) {
                throw state;
            }
            throw failure("解析文件快照失败", failure);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw failure("文件快照字符串超过大小限制", null);
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw failure("文件快照字符串长度无效", null);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("文件快照字符串不完整");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private Path snapshotPath(String clientKey) {
        return directory.resolve(fileName(clientKey) + ".snapshot");
    }

    private static String fileName(String clientKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(clientKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", impossible);
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由操作系统默认权限模型负责访问控制。
        }
    }

    private <T> CompletionStage<T> async(Supplier<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try {
                    result.complete(operation.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure("文件存储任务队列已满或已经关闭", failure));
        }
        return result;
    }

    private static String requiredKey(String clientKey) {
        if (clientKey == null || clientKey.isBlank()) {
            throw new IllegalArgumentException("客户端唯一键不能为空");
        }
        return clientKey;
    }

    private static IllegalStateException failure(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    /** 停止接收新的文件任务；已经提交的任务会继续执行。 */
    @Override
    public void close() {
        worker.shutdown();
    }
}
