/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.http.okhttp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.wxbot.ilink.api.exception.SessionExpiredException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.login.LoginPhase;
import io.github.wxbot.ilink.api.media.MediaDigest;
import io.github.wxbot.ilink.api.media.MediaType;
import io.github.wxbot.ilink.api.media.MediaUploadRequest;
import io.github.wxbot.ilink.api.message.ContextReference;
import io.github.wxbot.ilink.api.message.OutboundMessageType;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.CompositeMessageItem;
import io.github.wxbot.ilink.api.session.BotSession;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ILinkOkHttpProtocolTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;
    private URI baseUri;
    private ILinkOkHttpProtocol protocol;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        protocol = new ILinkOkHttpProtocol(new OkHttpClient(), baseUri,
                "2.0.0", "route-A", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        if (protocol != null) {
            protocol.close();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldEncodeQrCodeAndRecognizeBothScannedSpellings() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/ilink/bot/get_qrcode_status", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "{\"status\":\"scaned\"}");
        });

        assertEquals(LoginPhase.SCANNED,
                protocol.queryQrCodeStatus("a+b /中文").toCompletableFuture().join().phase());
        assertTrue(query.get().contains("qrcode=a%2Bb%20%2F%E4%B8%AD%E6%96%87"));
    }

    @Test
    void shouldMapUpdatesAndPreserveUnknownItemFields() throws Exception {
        server.createContext("/ilink/bot/getupdates", exchange -> respond(exchange, 200,
                "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[{"
                        + "\"message_id\":9,\"from_user_id\":\"u1\",\"to_user_id\":\"b1\","
                        + "\"create_time_ms\":1000,\"context_token\":\"ctx\","
                        + "\"item_list\":[{\"type\":99,\"future_field\":{\"x\":1}}]}]}"));

        var batch = protocol.poll(session(), "old").toCompletableFuture().join();
        assertEquals("next", batch.nextCursor());
        assertEquals(9L, batch.messages().get(0).messageId());
        assertTrue(batch.messages().get(0).items().get(0).attributes().containsKey("future_field"));
    }

    @Test
    void shouldSendStableClientIdAndSensitiveHeader() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server.createContext("/ilink/bot/sendmessage", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"ret\":0,\"message_id\":\"m-1\"}");
        });
        SendMessageRequest request = new SendMessageRequest(
                "client-fixed", "u1", OutboundMessageType.TEXT,
                ContextReference.explicit("ctx"), Map.of("text", "hello"));

        var receipt = protocol.send(session(), request).toCompletableFuture().join();
        assertEquals("client-fixed", receipt.clientId());
        assertEquals("Bearer secret-token", authorization.get());
        assertEquals("client-fixed", requestBody.get().at("/msg/client_id").asText());
        assertEquals("hello", requestBody.get().at("/msg/item_list/0/text_item/text").asText());
    }

    @Test
    void shouldClassifyExpiredAndRetryableHttpFailures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/ilink/bot/getupdates", exchange -> {
            if (calls.getAndIncrement() == 0) {
                respond(exchange, 200, "{\"ret\":-14}");
            } else {
                exchange.getResponseHeaders().set("Retry-After", "2");
                exchange.getResponseHeaders().set("X-Request-ID", "request-1");
                respond(exchange, 429, "busy");
            }
        });

        Throwable expired = failureOf(() -> protocol.poll(session(), ""));
        assertInstanceOf(SessionExpiredException.class, expired);

        Throwable busy = failureOf(() -> protocol.poll(session(), ""));
        TransportException transport = assertInstanceOf(TransportException.class, busy);
        assertTrue(transport.retryable());
        assertEquals(429, transport.httpStatus());
        assertEquals(java.time.Duration.ofSeconds(2), transport.retryAfter());
        assertEquals("request-1", transport.requestId());
        assertEquals("/ilink/bot/getupdates", transport.endpoint());
        assertTrue(!transport.getMessage().contains("secret-token"));
    }

    @Test
    void cancellingFutureShouldCancelUnderlyingHttpCall() throws Exception {
        server.createContext("/ilink/bot/getupdates", exchange -> {
            try {
                Thread.sleep(2_000L);
                respond(exchange, 200, "{\"ret\":0,\"get_updates_buf\":\"\",\"msgs\":[]}");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<?> future = protocol.poll(session(), "").toCompletableFuture();
        assertTrue(future.cancel(true));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (protocolClientRunningCalls() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(future.isCancelled());
        assertEquals(0, protocolClientRunningCalls());
    }

    @Test
    void shouldMapMediaUploadRequest() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server.createContext("/ilink/bot/getuploadurl", exchange -> {
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"ret\":0,\"upload_param\":\"grant\"}");
        });

        var grant = protocol.requestUpload(session(), new MediaUploadRequest(
                "file-key", MediaType.FILE, "u1", new MediaDigest(3, 16, "900150"),
                "00112233445566778899aabbccddeeff")).toCompletableFuture().join();
        assertEquals("grant", grant.encryptedQueryParameter());
        assertEquals(3, requestBody.get().path("media_type").asInt());
        assertEquals(16, requestBody.get().path("filesize").asInt());
        assertTrue(requestBody.get().path("no_need_thumb").asBoolean());
    }

    @Test
    void shouldEncodeCompositeAndUnknownOutboundItems() throws Exception {
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        server.createContext("/ilink/bot/sendmessage", exchange -> {
            requestBody.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange, 200, "{\"ret\":0,\"message_id\":\"m-2\"}");
        });
        SendMessageRequest request = SendMessageRequest.composite(
                "client-composite", "u1", java.util.List.of(
                        new CompositeMessageItem(1, "text_item", Map.of("text", "标题")),
                        new CompositeMessageItem(99, "future_item", Map.of("future", true))));
        request = new SendMessageRequest(request.clientId(), request.toUserId(), request.type(),
                ContextReference.explicit("ctx"), request.payload());

        protocol.send(session(), request).toCompletableFuture().join();

        assertEquals(2, requestBody.get().at("/msg/item_list").size());
        assertEquals(99, requestBody.get().at("/msg/item_list/1/type").asInt());
        assertTrue(requestBody.get().at("/msg/item_list/1/future_item/future").asBoolean());
    }

    @Test
    void shouldApplyDifferentDeadlinesToNormalAndLongPollRequests() throws Exception {
        server.createContext("/ilink/bot/get_bot_qrcode", exchange -> {
            sleep(150L);
            respond(exchange, 200, "{\"qrcode\":\"q\",\"qrcode_img_content\":\"image\"}");
        });
        server.createContext("/ilink/bot/getupdates", exchange -> {
            sleep(100L);
            respond(exchange, 200, "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[]}");
        });
        protocol.close();
        protocol = new ILinkOkHttpProtocol(new OkHttpClient(), baseUri,
                "2.0.0", null, Clock.systemUTC(),
                Duration.ofMillis(50), Duration.ofMillis(500), true);

        Throwable timeout = failureOf(protocol::requestQrCode);
        assertInstanceOf(TransportException.class, timeout);
        sleep(120L);
        assertEquals("next", protocol.poll(session(), "").toCompletableFuture().join().nextCursor());
    }

    @Test
    void 长轮询不应被调用方客户端较短的读取超时提前中断() throws Exception {
        server.createContext("/ilink/bot/getupdates", exchange -> {
            sleep(150L);
            respond(exchange, 200, "{\"ret\":0,\"get_updates_buf\":\"next\",\"msgs\":[]}");
        });
        protocol.close();
        OkHttpClient sharedClient = new OkHttpClient.Builder()
                .readTimeout(Duration.ofMillis(50))
                .build();
        protocol = new ILinkOkHttpProtocol(sharedClient, baseUri,
                "2.0.0", null, Clock.systemUTC(),
                Duration.ofMillis(200), Duration.ofMillis(500), false);

        assertEquals("next", protocol.poll(session(), "").toCompletableFuture().join().nextCursor());
        assertEquals(50, sharedClient.readTimeoutMillis());
    }

    @Test
    void closingBorrowedClientShouldOnlyCancelProtocolCalls() {
        OkHttpClient borrowed = new OkHttpClient();
        ILinkOkHttpProtocol borrowedProtocol = new ILinkOkHttpProtocol(borrowed, baseUri,
                "2.0.0", null, Clock.systemUTC(),
                Duration.ofSeconds(1), Duration.ofSeconds(2), false);

        borrowedProtocol.close();

        assertTrue(!borrowed.dispatcher().executorService().isShutdown());
        borrowed.dispatcher().executorService().shutdown();
        borrowed.connectionPool().evictAll();
    }

    private BotSession session() {
        return new BotSession("secret-token", "u", "b", baseUri);
    }

    private int protocolClientRunningCalls() throws Exception {
        java.lang.reflect.Field clientField = ILinkOkHttpProtocol.class.getDeclaredField("client");
        clientField.setAccessible(true);
        OkHttpClient client = (OkHttpClient) clientField.get(protocol);
        return client.dispatcher().runningCallsCount();
    }

    private static Throwable failureOf(StageSupplier supplier) {
        try {
            supplier.get().toCompletableFuture().join();
            throw new AssertionError("预期异步操作失败");
        } catch (CompletionException failure) {
            return failure.getCause();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待被中断", failure);
        }
    }

    @FunctionalInterface
    private interface StageSupplier {
        java.util.concurrent.CompletionStage<?> get();
    }
}
