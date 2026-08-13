/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.wxbot.ilink.api.media.CdnMedia;
import io.github.wxbot.ilink.api.media.MediaSource;
import io.github.wxbot.ilink.api.media.MediaType;
import io.github.wxbot.ilink.api.media.MediaUploadGrant;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.MediaProtocol;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingMediaClientTest {

    private HttpServer server;
    private URI baseUri;
    private StreamingMediaClient client;
    private final AtomicReference<byte[]> uploaded = new AtomicReference<>();
    private final AtomicReference<byte[]> downloadBody = new AtomicReference<>();
    private final AtomicReference<String> uploadQuery = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/c2c/upload", exchange -> {
            uploadQuery.set(exchange.getRequestURI().getRawQuery());
            uploaded.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("X-Encrypted-Param", "download grant /+中文");
            respond(exchange, 200, new byte[0]);
        });
        server.createContext("/c2c/download", exchange ->
                respond(exchange, 200, downloadBody.get()));
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/c2c/");
        MediaProtocol protocol = (session, request) ->
                CompletableFuture.completedFuture(new MediaUploadGrant("upload grant /+中文"));
        client = new StreamingMediaClient(new OkHttpClient(), protocol, baseUri,
                10 * 1024 * 1024, 2, 4, io.github.wxbot.ilink.api.observability.MetricsSink.noop());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void shouldStreamUploadAndDownloadWithEncodedParameters(@TempDir Path temp) throws Exception {
        byte[] plain = new byte[1024 * 1024 + 7];
        for (int index = 0; index < plain.length; index++) {
            plain[index] = (byte) (index * 31);
        }

        var uploadedMedia = client.upload(session(), "u1", MediaType.FILE, MediaSource.of(plain))
                .toCompletableFuture().join();
        assertEquals(StreamingMediaCrypto.encryptedLength(plain.length), uploaded.get().length);
        assertTrue(uploadQuery.get().contains(
                "encrypted_query_param=upload%20grant%20%2F%2B%E4%B8%AD%E6%96%87"));

        downloadBody.set(uploaded.get());
        Path target = temp.resolve("nested/result.bin");
        client.download(uploadedMedia.media(), target).toCompletableFuture().join();
        assertArrayEquals(plain, Files.readAllBytes(target));
        assertEquals(0, Files.list(target.getParent())
                .filter(path -> path.getFileName().toString().contains(".part-")).count());
    }

    @Test
    void shouldKeepExistingTargetWhenIntegrityCheckFails(@TempDir Path temp) throws Exception {
        byte[] plain = "new-content".getBytes(StandardCharsets.UTF_8);
        var uploadedMedia = client.upload(session(), "u1", MediaType.IMAGE, MediaSource.of(plain))
                .toCompletableFuture().join();
        downloadBody.set(uploaded.get());
        Path target = temp.resolve("result.bin");
        Files.writeString(target, "old-content");
        CdnMedia corruptedExpectation = new CdnMedia(
                uploadedMedia.media().encryptedQueryParameter(), uploadedMedia.media().aesKey(),
                1, plain.length, "00000000000000000000000000000000");

        assertThrows(CompletionException.class,
                () -> client.download(corruptedExpectation, target).toCompletableFuture().join());
        assertEquals("old-content", Files.readString(target));
        assertEquals(1, Files.list(temp).count());
    }

    @Test
    void shouldStreamDownloadWithoutClosingCallerOutput() throws Exception {
        byte[] plain = "stream-output".getBytes(StandardCharsets.UTF_8);
        var reference = client.upload(session(), "u1", MediaType.FILE, MediaSource.of(plain))
                .toCompletableFuture().join();
        downloadBody.set(uploaded.get());
        class RecordingOutput extends ByteArrayOutputStream {
            private boolean closed;

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }
        RecordingOutput output = new RecordingOutput();

        long bytes = client.download(reference.media(), output).toCompletableFuture().join();

        assertEquals(plain.length, bytes);
        assertArrayEquals(plain, output.toByteArray());
        assertFalse(output.closed);
    }

    @Test
    void shouldRejectSourceAboveConfiguredLimit() {
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        CompletionException failure = assertThrows(CompletionException.class,
                () -> client.upload(session(), "u1", MediaType.FILE, MediaSource.of(tooLarge))
                        .toCompletableFuture().join());
        assertTrue(failure.getCause().getMessage().contains("大小上限"));
        assertFalse(failure.getCause().getMessage().contains(HexFormat.of().formatHex(tooLarge, 0, 16)));
    }

    @Test
    void shouldPropagateUploadCancellationToProtocol() {
        CompletableFuture<MediaUploadGrant> grant = new CompletableFuture<>();
        StreamingMediaClient cancellable = new StreamingMediaClient(
                new OkHttpClient(), (session, request) -> grant, baseUri,
                1024, 1, 2, Duration.ofSeconds(5), false,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
        try {
            CompletableFuture<?> upload = cancellable.upload(
                    session(), "u1", MediaType.FILE, MediaSource.of(new byte[]{1, 2, 3}))
                    .toCompletableFuture();
            await(() -> grant.getNumberOfDependents() > 0);

            assertTrue(upload.cancel(true));
            await(grant::isCancelled);
            assertTrue(grant.isCancelled());
        } finally {
            cancellable.close();
        }
    }

    @Test
    void shouldCancelRunningDownloadOnCloseAndKeepSharedHttpClient() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/c2c/download");
        server.createContext("/c2c/download", exchange -> {
            started.countDown();
            try {
                release.await();
                respond(exchange, 200, new byte[16]);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        OkHttpClient shared = new OkHttpClient();
        StreamingMediaClient cancellable = new StreamingMediaClient(
                shared, (session, request) -> CompletableFuture.completedFuture(
                new MediaUploadGrant("unused")), baseUri, 1024, 1, 2,
                Duration.ofSeconds(30), false,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
        CompletableFuture<?> download = cancellable.download(media(),
                Files.createTempDirectory("media-close-").resolve("result.bin")).toCompletableFuture();
        assertTrue(started.await(2, TimeUnit.SECONDS));

        cancellable.close();
        release.countDown();

        assertTrue(download.isCancelled());
        assertFalse(shared.dispatcher().executorService().isShutdown());
        assertThrows(CancellationException.class, download::join);
    }

    @Test
    void shouldApplyIndependentCallTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.removeContext("/c2c/download");
        server.createContext("/c2c/download", exchange -> {
            started.countDown();
            try {
                release.await();
                respond(exchange, 200, new byte[16]);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        StreamingMediaClient timed = new StreamingMediaClient(
                new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(1)).build(),
                (session, request) -> CompletableFuture.completedFuture(new MediaUploadGrant("unused")),
                baseUri, 1024, 1, 2, Duration.ofMillis(100), false,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
        try {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> timed.download(media(), Files.createTempDirectory("media-timeout-")
                                    .resolve("result.bin"))
                            .toCompletableFuture().join());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("网络失败"));
        } finally {
            release.countDown();
            timed.close();
        }
    }

    @Test
    void shouldBoundConcurrentTransfersAndRejectOverflow() throws Exception {
        CountDownLatch firstGrantStarted = new CountDownLatch(1);
        CompletableFuture<MediaUploadGrant> blockedGrant = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        MediaProtocol blockingProtocol = (session, request) -> {
            calls.incrementAndGet();
            firstGrantStarted.countDown();
            return blockedGrant;
        };
        StreamingMediaClient bounded = new StreamingMediaClient(
                new OkHttpClient(), blockingProtocol, baseUri, 1024,
                1, 1, Duration.ofSeconds(5), false,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
        try {
            CompletableFuture<?> first = bounded.upload(session(), "u1", MediaType.FILE,
                    MediaSource.of(new byte[]{1})).toCompletableFuture();
            assertTrue(firstGrantStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<?> queued = bounded.upload(session(), "u2", MediaType.FILE,
                    MediaSource.of(new byte[]{2})).toCompletableFuture();
            CompletableFuture<?> rejected = bounded.upload(session(), "u3", MediaType.FILE,
                    MediaSource.of(new byte[]{3})).toCompletableFuture();

            CompletionException failure = assertThrows(CompletionException.class, rejected::join);
            assertTrue(failure.getCause().getMessage().contains("等待队列已满"));
            assertEquals(1, calls.get());
            assertFalse(queued.isDone());
            first.cancel(true);
            await(() -> calls.get() == 2);
            queued.cancel(true);
        } finally {
            bounded.close();
        }
    }

    @Test
    void shouldReleasePendingCapacityImmediatelyAfterCancellation() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CompletableFuture<MediaUploadGrant> firstGrant = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        MediaProtocol protocol = (session, request) -> {
            if (calls.incrementAndGet() == 1) {
                firstStarted.countDown();
                return firstGrant;
            }
            return new CompletableFuture<>();
        };
        StreamingMediaClient bounded = new StreamingMediaClient(
                new OkHttpClient(), protocol, baseUri, 1024,
                1, 1, Duration.ofSeconds(5), false,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
        try {
            CompletableFuture<?> first = bounded.upload(session(), "u1", MediaType.FILE,
                    MediaSource.of(new byte[]{1})).toCompletableFuture();
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            CompletableFuture<?> cancelled = bounded.upload(session(), "u2", MediaType.FILE,
                    MediaSource.of(new byte[]{2})).toCompletableFuture();
            assertTrue(cancelled.cancel(true));

            CompletableFuture<?> replacement = bounded.upload(session(), "u3", MediaType.FILE,
                    MediaSource.of(new byte[]{3})).toCompletableFuture();

            assertFalse(replacement.isCompletedExceptionally());
            first.cancel(true);
            replacement.cancel(true);
        } finally {
            bounded.close();
        }
    }

    private CdnMedia media() {
        String key = java.util.Base64.getEncoder().encodeToString(new byte[16]);
        return new CdnMedia("download", key, 1, -1, null);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean());
    }

    private BotSession session() {
        return new BotSession("token", "u", "b", baseUri);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
