/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.media;

import io.github.wxbot.ilink.api.exception.ClientClosedException;
import io.github.wxbot.ilink.api.exception.MediaException;
import io.github.wxbot.ilink.api.media.CdnMedia;
import io.github.wxbot.ilink.api.media.MediaDigest;
import io.github.wxbot.ilink.api.media.MediaSource;
import io.github.wxbot.ilink.api.media.MediaType;
import io.github.wxbot.ilink.api.media.MediaUploadRequest;
import io.github.wxbot.ilink.api.media.UploadedMedia;
import io.github.wxbot.ilink.api.observability.MetricsSink;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.MediaProtocol;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 有界、流式的 iLink CDN 媒体客户端。
 *
 * <p>上传会先流式计算摘要，再由 OkHttp 在写网络请求时实时加密；下载写入同目录的随机 {@code .part} 文件，
 * 只有 AES 填充、长度和可选 MD5 全部校验后才原子替换目标。额外堆内存与文件大小无关。
 */
public final class StreamingMediaClient implements AutoCloseable {

    private static final URI DEFAULT_CDN_BASE = URI.create("https://novac2c.cdn.weixin.qq.com/c2c/");
    private static final okhttp3.MediaType BINARY = okhttp3.MediaType.get("application/octet-stream");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Map<String, String> NO_TAGS = Map.of();

    private final OkHttpClient client;
    private final MediaProtocol protocol;
    private final URI cdnBaseUri;
    private final long maximumBytes;
    private final MetricsSink metrics;
    private final ThreadPoolExecutor fileWorkers;
    private final long callTimeoutNanos;
    private final boolean ownsClient;
    private final int maximumConcurrentTransfers;
    private final int pendingTransferCapacity;
    private final Object transferLock = new Object();
    private final ArrayDeque<PendingTransfer> pendingTransfers = new ArrayDeque<>();
    private int activeTransfers;
    private final Set<MediaOperation<?>> operations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 使用默认 CDN 地址、2 个文件线程和 512 MiB 大小上限。 */
    public StreamingMediaClient(OkHttpClient client, MediaProtocol protocol) {
        this(client, protocol, DEFAULT_CDN_BASE, 512L * 1024 * 1024, 2, 16,
                Duration.ofMinutes(5), false, MetricsSink.noop());
    }

    /** 创建资源边界明确的媒体客户端。 */
    public StreamingMediaClient(
            OkHttpClient client,
            MediaProtocol protocol,
            URI cdnBaseUri,
            long maximumBytes,
            int workerCount,
            int queueCapacity,
            MetricsSink metrics) {
        this(client, protocol, cdnBaseUri, maximumBytes, workerCount, queueCapacity,
                Duration.ofMinutes(5), false, metrics);
    }

    /**
     * 创建可配置传输期限和 HTTP 客户端所有权的媒体客户端。
     *
     * @param callTimeout 每次 CDN 上传或下载的完整调用期限
     * @param ownsClient 关闭媒体客户端时是否同时释放传入的 OkHttp 客户端资源
     */
    public StreamingMediaClient(
            OkHttpClient client,
            MediaProtocol protocol,
            URI cdnBaseUri,
            long maximumBytes,
            int workerCount,
            int queueCapacity,
            Duration callTimeout,
            boolean ownsClient,
            MetricsSink metrics) {
        this.client = Objects.requireNonNull(client, "OkHttp 客户端不能为空");
        this.protocol = Objects.requireNonNull(protocol, "媒体协议不能为空");
        this.cdnBaseUri = Objects.requireNonNull(cdnBaseUri, "CDN 基础地址不能为空");
        if (!cdnBaseUri.isAbsolute() || maximumBytes <= 0 || workerCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("CDN 地址和媒体资源边界不合法");
        }
        this.maximumBytes = maximumBytes;
        Objects.requireNonNull(callTimeout, "媒体调用超时不能为空");
        if (callTimeout.isZero() || callTimeout.isNegative()) {
            throw new IllegalArgumentException("媒体调用超时必须大于零");
        }
        try {
            this.callTimeoutNanos = callTimeout.toNanos();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("媒体调用超时超出可表示范围", failure);
        }
        this.ownsClient = ownsClient;
        this.maximumConcurrentTransfers = workerCount;
        this.pendingTransferCapacity = queueCapacity;
        this.metrics = Objects.requireNonNull(metrics, "指标出口不能为空");
        this.fileWorkers = new ThreadPoolExecutor(
                workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, "wxbot-ilink-media-file");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 流式上传媒体。
     *
     * <p>数据源必须可重复打开，因为摘要和上传分别读取一次。失败后可以使用同一数据源重新调用。
     */
    public CompletionStage<UploadedMedia> upload(
            BotSession session, String toUserId, MediaType type, MediaSource source) {
        ensureOpen();
        Objects.requireNonNull(session, "Bot 会话不能为空");
        Objects.requireNonNull(type, "媒体类型不能为空");
        Objects.requireNonNull(source, "媒体源不能为空");
        MediaOperation<UploadedMedia> result = newOperation();
        submitTransfer(() -> {
            try {
                // 先检查声明长度，避免对必然拒绝的超大文件执行完整摘要扫描。
                validateSize(source.contentLength());
                MediaDigest digest = StreamingMediaCrypto.digest(source);
                validateSize(digest.rawLength());
                byte[] key = randomKey();
                String keyHex = HexFormat.of().formatHex(key);
                String fileKey = randomFileKey();
                MediaUploadRequest request = new MediaUploadRequest(
                        fileKey, type, required(toUserId, "接收方用户标识"), digest, keyHex);
                CompletableFuture<io.github.wxbot.ilink.api.media.MediaUploadGrant> grantFuture =
                        Objects.requireNonNull(protocol.requestUpload(session, request),
                                "媒体协议不能返回空阶段").toCompletableFuture();
                result.attachProtocol(grantFuture);
                grantFuture.whenComplete((grant, failure) -> {
                    result.detachProtocol(grantFuture);
                    if (result.isDone()) {
                        return;
                    }
                    if (failure != null) {
                        result.completeExceptionally(unwrap(failure));
                        return;
                    }
                    uploadToCdn(source, digest, key, fileKey, grant.encryptedQueryParameter(), result);
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure instanceof MediaException
                        ? failure : mediaFailure("ILINK-MEDIA-001", "读取媒体源失败", false, failure));
            }
        }, result);
        return result;
    }

    /**
     * 流式下载并安全替换目标文件。
     *
     * <p>失败时目标文件保持原样，SDK 会删除自身创建的临时文件。
     */
    public CompletionStage<Path> download(CdnMedia media, Path target) {
        ensureOpen();
        Objects.requireNonNull(media, "CDN 媒体不能为空");
        Objects.requireNonNull(target, "下载目标不能为空");
        byte[] key = decodeKey(media.aesKey());
        HttpUrl url = endpoint("download").newBuilder()
                .addQueryParameter("encrypted_query_param", media.encryptedQueryParameter()).build();
        MediaOperation<Path> result = newOperation();
        submitTransfer(() -> startDownload(media, target, key, url, result), result);
        return result;
    }

    /**
     * 将媒体流式下载到调用方提供的输出流。
     *
     * <p>SDK 不关闭输出流。长度与摘要在全部内容写出后校验，因此校验失败时输出流可能已包含不完整数据；需要
     * “失败不改变目标”语义时应使用 {@link #download(CdnMedia, Path)}。
     */
    public CompletionStage<Long> download(CdnMedia media, OutputStream output) {
        ensureOpen();
        Objects.requireNonNull(media, "CDN 媒体不能为空");
        Objects.requireNonNull(output, "下载输出流不能为空");
        byte[] key = decodeKey(media.aesKey());
        HttpUrl url = endpoint("download").newBuilder()
                .addQueryParameter("encrypted_query_param", media.encryptedQueryParameter()).build();
        MediaOperation<Long> result = newOperation();
        submitTransfer(() -> startStreamDownload(media, output, key, url, result), result);
        return result;
    }

    private void startStreamDownload(
            CdnMedia media,
            OutputStream output,
            byte[] key,
            HttpUrl url,
            MediaOperation<Long> result) {
        if (result.isDone()) {
            return;
        }
        Call call = timedCall(new Request.Builder().url(url).get().build());
        result.attachCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call failedCall, IOException failure) {
                result.detachCall(failedCall);
                if (!result.isDone()) {
                    result.completeExceptionally(mediaFailure(
                            "ILINK-MEDIA-002", "CDN 下载网络失败", true, failure));
                }
            }

            @Override
            public void onResponse(Call completedCall, Response response) {
                if (result.isDone()) {
                    response.close();
                    return;
                }
                result.whenComplete((ignored, failure) -> response.close());
                submitFileTask(() -> consumeStreamDownload(
                        response, media, output, key, result, completedCall), result);
            }
        });
    }

    private void startDownload(
            CdnMedia media, Path target, byte[] key, HttpUrl url, MediaOperation<Path> result) {
        if (result.isDone()) {
            return;
        }
        Call call = timedCall(new Request.Builder().url(url).get().build());
        result.attachCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException failure) {
                result.detachCall(call);
                if (result.isDone()) {
                    return;
                }
                result.completeExceptionally(mediaFailure(
                        "ILINK-MEDIA-002", "CDN 下载网络失败", true, failure));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (result.isDone()) {
                    response.close();
                    return;
                }
                // 取消发生在响应到达与文件线程接管之间时，也必须关闭响应体和底层连接。
                result.whenComplete((ignored, failure) -> response.close());
                try {
                    submitFileTask(
                            () -> consumeDownload(response, media, target, key, result, call), result);
                } catch (Throwable failure) {
                    response.close();
                    result.completeExceptionally(failure);
                }
            }
        });
    }

    private void uploadToCdn(
            MediaSource source,
            MediaDigest digest,
            byte[] key,
            String fileKey,
            String uploadParameter,
            MediaOperation<UploadedMedia> result) {
        HttpUrl url = endpoint("upload").newBuilder()
                .addQueryParameter("encrypted_query_param", uploadParameter)
                .addQueryParameter("filekey", fileKey).build();
        RequestBody body = new RequestBody() {
            @Override
            public okhttp3.MediaType contentType() {
                return BINARY;
            }

            @Override
            public long contentLength() {
                return digest.encryptedLength();
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                try (InputStream input = source.openStream()) {
                    long raw = StreamingMediaCrypto.encrypt(input, sink.outputStream(), key);
                    if (raw != digest.rawLength()) {
                        throw new IOException("媒体长度在上传期间发生变化");
                    }
                }
            }
        };
        if (result.isDone()) {
            return;
        }
        Call call = timedCall(new Request.Builder().url(url).post(body).build());
        result.attachCall(call);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException failure) {
                result.detachCall(call);
                if (result.isDone()) {
                    return;
                }
                result.completeExceptionally(mediaFailure(
                        "ILINK-MEDIA-003", "CDN 上传网络失败", true, failure));
            }

            @Override
            public void onResponse(Call call, Response response) {
                result.detachCall(call);
                if (result.isDone()) {
                    response.close();
                    return;
                }
                try (response) {
                    if (!response.isSuccessful()) {
                        result.completeExceptionally(mediaFailure(
                                "ILINK-MEDIA-HTTP-" + response.code(),
                                "CDN 上传失败，状态码=" + response.code(),
                                response.code() == 429 || response.code() >= 500, null));
                        return;
                    }
                    String encryptedParameter = response.header("X-Encrypted-Param");
                    if (encryptedParameter == null || encryptedParameter.isBlank()) {
                        result.completeExceptionally(mediaFailure(
                                "ILINK-MEDIA-004", "CDN 上传响应缺少必要参数", false, null));
                        return;
                    }
                    String encodedKey = Base64.getEncoder().encodeToString(
                            HexFormat.of().formatHex(key).getBytes(StandardCharsets.UTF_8));
                    metrics.increment("ilink.media.upload.bytes", NO_TAGS);
                    result.complete(new UploadedMedia(fileKey,
                            new CdnMedia(encryptedParameter, encodedKey, 1,
                                    digest.rawLength(), digest.md5Hex()), digest));
                }
            }
        });
    }

    private void consumeDownload(
            Response response,
            CdnMedia media,
            Path target,
            byte[] key,
            MediaOperation<Path> result,
            Call call) {
        Path part = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());
        try (response; ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) {
                throw mediaFailure("ILINK-MEDIA-HTTP-" + response.code(),
                        "CDN 下载失败，状态码=" + response.code(),
                        response.code() == 429 || response.code() >= 500, null);
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MessageDigest md5 = md5();
            long raw;
            try (InputStream input = body.byteStream();
                 OutputStream file = Files.newOutputStream(part);
                 java.security.DigestOutputStream checked =
                         new java.security.DigestOutputStream(file, md5)) {
                raw = StreamingMediaCrypto.decrypt(input, checked, key);
            }
            String actualMd5 = HexFormat.of().formatHex(md5.digest());
            validateDownload(media, raw, actualMd5);
            if (result.commit(target, () -> moveAtomically(part, target))) {
                metrics.increment("ilink.media.download.bytes", NO_TAGS);
            } else {
                Files.deleteIfExists(part);
            }
        } catch (Throwable failure) {
            try {
                Files.deleteIfExists(part);
            } catch (IOException ignored) {
                // 临时文件清理失败不覆盖原始下载异常。
            }
            result.completeExceptionally(failure instanceof MediaException
                    ? failure : mediaFailure("ILINK-MEDIA-007", "媒体下载或解密失败", false, failure));
        } finally {
            result.detachCall(call);
        }
    }

    private void consumeStreamDownload(
            Response response,
            CdnMedia media,
            OutputStream output,
            byte[] key,
            MediaOperation<Long> result,
            Call call) {
        try (response; ResponseBody body = response.body()) {
            if (!response.isSuccessful() || body == null) {
                throw mediaFailure("ILINK-MEDIA-HTTP-" + response.code(),
                        "CDN 下载失败，状态码=" + response.code(),
                        response.code() == 429 || response.code() >= 500, null);
            }
            MessageDigest digest = md5();
            long raw;
            try (InputStream input = body.byteStream()) {
                java.security.DigestOutputStream checked =
                        new java.security.DigestOutputStream(output, digest);
                raw = StreamingMediaCrypto.decrypt(input, checked, key);
                checked.flush();
            }
            validateDownload(media, raw, HexFormat.of().formatHex(digest.digest()));
            metrics.increment("ilink.media.download.bytes", NO_TAGS);
            result.complete(raw);
        } catch (Throwable failure) {
            result.completeExceptionally(failure instanceof MediaException
                    ? failure : mediaFailure("ILINK-MEDIA-007", "媒体下载或解密失败", false, failure));
        } finally {
            result.detachCall(call);
        }
    }

    private void submitFileTask(Runnable task, MediaOperation<?> result) {
        FutureTask<Void> worker = new FutureTask<>(() -> {
            if (!result.isDone()) {
                task.run();
            }
            return null;
        }) {
            @Override
            protected void done() {
                result.detachWorker(this);
            }
        };
        result.attachWorker(worker);
        if (result.isDone()) {
            result.detachWorker(worker);
            worker.cancel(true);
            return;
        }
        try {
            fileWorkers.execute(worker);
        } catch (RejectedExecutionException failure) {
            result.detachWorker(worker);
            worker.cancel(false);
            if (!result.isDone()) {
                result.completeExceptionally(mediaFailure(
                        "ILINK-MEDIA-008", "媒体文件任务队列已满", true, failure));
            }
        }
    }

    private void submitTransfer(Runnable task, MediaOperation<?> result) {
        PendingTransfer accepted = null;
        boolean cancel = false;
        boolean overflow = false;
        synchronized (transferLock) {
            if (closed.get()) {
                cancel = true;
            } else if (result.isDone()) {
                return;
            } else if (activeTransfers < maximumConcurrentTransfers) {
                activeTransfers++;
                result.registerTransferSlot();
                accepted = new PendingTransfer(task, result);
            } else if (pendingTransfers.size() < pendingTransferCapacity) {
                pendingTransfers.addLast(new PendingTransfer(task, result));
            } else {
                overflow = true;
            }
        }
        if (cancel) {
            result.cancel(true);
        } else if (overflow) {
            result.completeExceptionally(mediaFailure(
                    "ILINK-MEDIA-008", "媒体传输等待队列已满", true, null));
        }
        if (accepted != null) {
            submitFileTask(accepted.task(), accepted.result());
        }
    }

    private void finishTransfer(MediaOperation<?> completed) {
        PendingTransfer next = null;
        synchronized (transferLock) {
            // 等待中的操作取消后立即释放队列容量，不必等待某个活动传输完成。
            pendingTransfers.removeIf(candidate -> candidate.result() == completed);
            if (completed.releaseTransferSlot()) {
                activeTransfers--;
            }
            if (closed.get()) {
                return;
            }
            while (activeTransfers < maximumConcurrentTransfers && !pendingTransfers.isEmpty()) {
                PendingTransfer candidate = pendingTransfers.removeFirst();
                if (candidate.result().isDone()) {
                    continue;
                }
                activeTransfers++;
                candidate.result().registerTransferSlot();
                next = candidate;
                break;
            }
        }
        if (next != null) {
            submitFileTask(next.task(), next.result());
        }
    }

    private Call timedCall(Request request) {
        Call call = client.newCall(request);
        call.timeout().timeout(callTimeoutNanos, TimeUnit.NANOSECONDS);
        return call;
    }

    private <T> MediaOperation<T> newOperation() {
        MediaOperation<T> operation = new MediaOperation<>();
        operations.add(operation);
        operation.whenComplete((ignored, failure) -> {
            operations.remove(operation);
            finishTransfer(operation);
        });
        if (closed.get()) {
            operation.cancel(true);
        }
        return operation;
    }

    private HttpUrl endpoint(String operation) {
        HttpUrl base = HttpUrl.get(cdnBaseUri);
        return base.newBuilder().addPathSegment(operation).build();
    }

    private void validateSize(long bytes) {
        if (bytes <= 0 || bytes > maximumBytes) {
            throw mediaFailure("ILINK-MEDIA-009", "媒体为空或超过配置的大小上限", false, null);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[16];
        SECURE_RANDOM.nextBytes(key);
        return key;
    }

    private static String randomFileKey() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static byte[] decodeKey(String encoded) {
        try {
            byte[] decoded = Base64.getDecoder().decode(required(encoded, "AES 密钥"));
            if (decoded.length == 16) {
                return decoded;
            }
            byte[] key = HexFormat.of().parseHex(new String(decoded, StandardCharsets.UTF_8));
            if (key.length != 16) {
                throw new IllegalArgumentException("密钥长度不正确");
            }
            return key;
        } catch (RuntimeException failure) {
            throw mediaFailure("ILINK-MEDIA-010", "媒体 AES 密钥格式不合法", false, failure);
        }
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 Java 运行时不支持 MD5", failure);
        }
    }

    private static void validateDownload(CdnMedia media, long raw, String actualMd5) {
        if (media.rawLength() >= 0 && raw != media.rawLength()) {
            throw mediaFailure("ILINK-MEDIA-005", "下载媒体长度校验失败", false, null);
        }
        if (media.md5Hex() != null && !media.md5Hex().equalsIgnoreCase(actualMd5)) {
            throw mediaFailure("ILINK-MEDIA-006", "下载媒体摘要校验失败", false, null);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static MediaException mediaFailure(
            String code, String message, boolean retryable, Throwable cause) {
        return new MediaException(code, message, retryable, cause);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new ClientClosedException("媒体客户端");
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            operations.forEach(operation -> operation.cancel(true));
            synchronized (transferLock) {
                pendingTransfers.clear();
            }
            fileWorkers.shutdownNow();
            if (ownsClient) {
                client.dispatcher().cancelAll();
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
                if (client.cache() != null) {
                    try {
                        client.cache().close();
                    } catch (IOException ignored) {
                        // 关闭缓存失败不影响其他资源继续释放。
                    }
                }
            }
        }
    }

    /** 追踪单次媒体操作关联的协议、文件线程和 CDN 请求，统一传播取消。 */
    private static final class MediaOperation<T> extends CompletableFuture<T> {
        private final AtomicReference<CompletableFuture<?>> protocol = new AtomicReference<>();
        private final AtomicReference<Future<?>> worker = new AtomicReference<>();
        private final AtomicReference<Call> call = new AtomicReference<>();
        private boolean transferSlot;
        private final Object completionLock = new Object();

        private synchronized void registerTransferSlot() {
            transferSlot = true;
        }

        private synchronized boolean releaseTransferSlot() {
            if (!transferSlot) {
                return false;
            }
            transferSlot = false;
            return true;
        }

        private void attachProtocol(CompletableFuture<?> value) {
            protocol.set(value);
            cancelIfNeeded(value);
        }

        private void detachProtocol(CompletableFuture<?> value) {
            protocol.compareAndSet(value, null);
        }

        private void attachWorker(Future<?> value) {
            worker.set(value);
            cancelIfNeeded(value);
        }

        private void detachWorker(Future<?> value) {
            if (value != null) {
                worker.compareAndSet(value, null);
            }
        }

        private void attachCall(Call value) {
            Call previous = call.getAndSet(value);
            if (previous != null && previous != value) {
                previous.cancel();
            }
            if (isCancelled()) {
                value.cancel();
            }
        }

        private void detachCall(Call value) {
            call.compareAndSet(value, null);
        }

        private void cancelIfNeeded(Future<?> value) {
            if (isCancelled()) {
                value.cancel(true);
            }
        }

        /** 在取消互斥区内提交不可回滚的文件替换，并同时发布成功结果。 */
        private boolean commit(T value, CommitAction action) throws IOException {
            synchronized (completionLock) {
                if (isDone()) {
                    return false;
                }
                action.run();
                return super.complete(value);
            }
        }

        @Override
        public boolean complete(T value) {
            synchronized (completionLock) {
                return super.complete(value);
            }
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            synchronized (completionLock) {
                return super.completeExceptionally(failure);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled;
            synchronized (completionLock) {
                cancelled = super.cancel(mayInterruptIfRunning);
            }
            if (cancelled) {
                CompletableFuture<?> activeProtocol = protocol.getAndSet(null);
                if (activeProtocol != null) {
                    activeProtocol.cancel(mayInterruptIfRunning);
                }
                Future<?> activeWorker = worker.getAndSet(null);
                if (activeWorker != null) {
                    activeWorker.cancel(mayInterruptIfRunning);
                }
                Call activeCall = call.getAndSet(null);
                if (activeCall != null) {
                    activeCall.cancel();
                }
            }
            return cancelled;
        }
    }

    /** 尚未取得并发名额的传输起始任务。 */
    private record PendingTransfer(Runnable task, MediaOperation<?> result) {
    }

    /** 可在媒体完成锁内执行并报告 IO 失败的提交动作。 */
    @FunctionalInterface
    private interface CommitAction {
        void run() throws IOException;
    }
}
