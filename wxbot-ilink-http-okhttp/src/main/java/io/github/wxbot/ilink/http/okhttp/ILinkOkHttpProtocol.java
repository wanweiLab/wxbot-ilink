/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.http.okhttp;

import io.github.wxbot.ilink.api.exception.ClientClosedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wxbot.ilink.api.exception.SessionExpiredException;
import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.login.LoginPollResult;
import io.github.wxbot.ilink.api.login.QrCode;
import io.github.wxbot.ilink.api.message.ContextReference;
import io.github.wxbot.ilink.api.message.CompositeMessageItem;
import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.MessageItem;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.UpdateBatch;
import io.github.wxbot.ilink.api.media.MediaUploadGrant;
import io.github.wxbot.ilink.api.media.MediaUploadRequest;
import io.github.wxbot.ilink.api.session.BotSession;
import io.github.wxbot.ilink.api.transport.LoginProtocol;
import io.github.wxbot.ilink.api.transport.MessageProtocol;
import io.github.wxbot.ilink.api.transport.MediaProtocol;
import io.github.wxbot.ilink.api.transport.UpdateProtocol;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 基于 OkHttp 和 Jackson 的 iLink 协议实现。
 *
 * <p>该类只完成协议字段映射和异步 HTTP 调用，不实现业务重试、登录轮询和消息分发。所有 OkHttp 回调只用于
 * 完成 {@link CompletableFuture}，用户消息处理器不会运行在 OkHttp 线程中。调用方传入客户端时默认不转移
 * 资源所有权；关闭协议对象只取消本对象发起的在途请求。
 */
public final class ILinkOkHttpProtocol
        implements LoginProtocol, UpdateProtocol, MessageProtocol, MediaProtocol, AutoCloseable {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Logger LOGGER = LoggerFactory.getLogger(ILinkOkHttpProtocol.class);
    private static final URI DEFAULT_LOGIN_BASE = URI.create("https://ilinkai.weixin.qq.com");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DEFAULT_LONG_POLL_TIMEOUT = Duration.ofSeconds(35);

    private final OkHttpClient client;
    private final ObjectMapper mapper;
    private final URI loginBaseUri;
    private final String channelVersion;
    private final String routeTag;
    private final Clock clock;
    private final Duration requestTimeout;
    private final Duration longPollTimeout;
    private final boolean ownsClient;
    private final Set<Call> runningCalls = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** 使用官方登录地址和默认协议版本创建实现。 */
    public ILinkOkHttpProtocol(OkHttpClient client) {
        this(client, DEFAULT_LOGIN_BASE, "1.0.0", null, Clock.systemUTC(),
                DEFAULT_REQUEST_TIMEOUT, DEFAULT_LONG_POLL_TIMEOUT, false);
    }

    /**
     * 创建可配置协议实现。
     *
     * @param client 由调用方管理生命周期的共享 OkHttp 客户端
     * @param loginBaseUri 登录接口基础地址
     * @param channelVersion 协议渠道版本
     * @param routeTag 可选路由标签
     * @param clock 生成回执时间的时钟
     */
    public ILinkOkHttpProtocol(
            OkHttpClient client,
            URI loginBaseUri,
            String channelVersion,
            String routeTag,
            Clock clock) {
        this(client, loginBaseUri, channelVersion, routeTag, clock,
                DEFAULT_REQUEST_TIMEOUT, DEFAULT_LONG_POLL_TIMEOUT, false);
    }

    /**
     * 创建带分层超时和明确资源所有权的协议实现。
     *
     * @param client OkHttp 客户端
     * @param loginBaseUri 登录接口基础地址
     * @param channelVersion 协议渠道版本
     * @param routeTag 可选路由标签
     * @param clock 生成回执时间的时钟
     * @param requestTimeout 登录、发送和配置请求的单次截止时间
     * @param longPollTimeout 消息长轮询的单次截止时间
     * @param ownsClient 关闭协议对象时是否同时释放客户端线程池和连接池
     */
    public ILinkOkHttpProtocol(
            OkHttpClient client,
            URI loginBaseUri,
            String channelVersion,
            String routeTag,
            Clock clock,
            Duration requestTimeout,
            Duration longPollTimeout,
            boolean ownsClient) {
        OkHttpClient sharedClient = Objects.requireNonNull(client, "OkHttp 客户端不能为空");
        this.loginBaseUri = absolute(loginBaseUri, "登录接口基础地址");
        this.channelVersion = required(channelVersion, "渠道版本");
        this.routeTag = routeTag;
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
        this.requestTimeout = positiveTimeout(requestTimeout, "普通请求超时");
        this.longPollTimeout = positiveTimeout(longPollTimeout, "长轮询超时");
        if (this.requestTimeout.compareTo(this.longPollTimeout) >= 0) {
            throw new IllegalArgumentException("普通请求超时必须小于长轮询超时");
        }
        // Call.timeout 只限制整次调用，不会覆盖 OkHttp 默认 10 秒的套接字读取超时。
        // 派生客户端继续共享连接池和调度器，同时允许无消息的长轮询等待到协议截止时间。
        this.client = sharedClient.newBuilder()
                .readTimeout(this.longPollTimeout)
                .writeTimeout(this.requestTimeout)
                .build();
        this.ownsClient = ownsClient;
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public CompletionStage<QrCode> requestQrCode() {
        HttpUrl url = url(loginBaseUri, "/ilink/bot/get_bot_qrcode")
                .newBuilder().addQueryParameter("bot_type", "3").build();
        Request.Builder request = new Request.Builder().url(url).get();
        addRouteTag(request);
        return map(execute(request.build(), requestTimeout), json -> new QrCode(
                text(json, "qrcode"),
                text(json, "qrcode_img_content"),
                clock.instant().plus(3, ChronoUnit.MINUTES)));
    }

    @Override
    public CompletionStage<LoginPollResult> queryQrCodeStatus(String qrCodeToken) {
        HttpUrl url = url(loginBaseUri, "/ilink/bot/get_qrcode_status")
                .newBuilder().addQueryParameter("qrcode", required(qrCodeToken, "二维码令牌")).build();
        Request.Builder request = new Request.Builder().url(url).get()
                .header("iLink-App-ClientVersion", "1");
        addRouteTag(request);
        return map(execute(request.build(), requestTimeout), json -> {
            String status = text(json, "status").toLowerCase(java.util.Locale.ROOT);
            return switch (status) {
                case "wait", "waiting" -> LoginPollResult.waiting();
                case "scaned", "scanned" -> LoginPollResult.scanned();
                case "expired" -> LoginPollResult.expired();
                case "confirmed" -> LoginPollResult.confirmed(new BotSession(
                        text(json, "bot_token"),
                        text(json, "ilink_user_id"),
                        text(json, "ilink_bot_id"),
                        URI.create(text(json, "baseurl"))));
                default -> throw new TransportException(
                        "ILINK-PROTOCOL-001", "无法识别的登录状态：" + status, false, null);
            };
        });
    }

    @Override
    public CompletionStage<UpdateBatch> poll(BotSession session, String cursor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("get_updates_buf", cursor == null ? "" : cursor);
        body.put("base_info", baseInfo());
        return map(businessPost(session, "/ilink/bot/getupdates", body, longPollTimeout), json -> {
            List<InboundMessage> messages = new ArrayList<>();
            JsonNode nodes = json.path("msgs");
            if (nodes.isArray()) {
                for (JsonNode node : nodes) {
                    messages.add(toInboundMessage(node));
                }
            }
            return new UpdateBatch(json.path("get_updates_buf").asText(""), messages);
        });
    }

    @Override
    public CompletionStage<SendReceipt> send(BotSession session, SendMessageRequest request) {
        if (request.context().mode() != ContextReference.Mode.EXPLICIT) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("协议层发送请求必须包含显式上下文令牌"));
        }
        List<Map<String, Object>> items = protocolItems(request);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from_user_id", "");
        message.put("to_user_id", request.toUserId());
        message.put("client_id", request.clientId());
        message.put("message_type", 2);
        message.put("message_state", 2);
        message.put("context_token", request.context().value());
        message.put("item_list", items);

        return map(businessPost(session, "/ilink/bot/sendmessage",
                Map.of("msg", message, "base_info", baseInfo())), json -> new SendReceipt(
                        request.clientId(), nullableText(json, "message_id"), clock.instant()));
    }

    @Override
    public CompletionStage<String> requestTypingTicket(
            BotSession session, String userId, String contextToken) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", required(userId, "用户标识"));
        body.put("context_token", required(contextToken, "上下文令牌"));
        body.put("base_info", baseInfo());
        return map(businessPost(session, "/ilink/bot/getconfig", body),
                json -> text(json, "typing_ticket"));
    }

    @Override
    public CompletionStage<Void> setTyping(
            BotSession session, String userId, String ticket, boolean typing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ilink_user_id", required(userId, "用户标识"));
        body.put("typing_ticket", required(ticket, "输入态票据"));
        body.put("status", typing ? 1 : 2);
        body.put("base_info", baseInfo());
        return map(businessPost(session, "/ilink/bot/sendtyping", body), ignored -> null);
    }

    @Override
    public CompletionStage<MediaUploadGrant> requestUpload(
            BotSession session, MediaUploadRequest request) {
        Objects.requireNonNull(request, "媒体上传请求不能为空");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("filekey", required(request.fileKey(), "文件键"));
        body.put("media_type", request.mediaType().protocolValue());
        body.put("to_user_id", required(request.toUserId(), "接收方用户标识"));
        body.put("rawsize", request.digest().rawLength());
        body.put("rawfilemd5", request.digest().md5Hex());
        body.put("filesize", request.digest().encryptedLength());
        body.put("no_need_thumb", true);
        body.put("aeskey", required(request.aesKeyHex(), "AES 密钥"));
        body.put("base_info", baseInfo());
        return map(businessPost(session, "/ilink/bot/getuploadurl", body),
                json -> new MediaUploadGrant(text(json, "upload_param")));
    }

    private CompletionStage<JsonNode> businessPost(
            BotSession session, String path, Object body) {
        return businessPost(session, path, body, requestTimeout);
    }

    private CompletionStage<JsonNode> businessPost(
            BotSession session, String path, Object body, Duration timeout) {
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(new TransportException(
                    "ILINK-PROTOCOL-002", "请求序列化失败", false, failure));
        }
        Request.Builder request = new Request.Builder()
                .url(url(session.baseUri(), path))
                .post(RequestBody.create(bytes, JSON))
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + session.botToken())
                .header("X-WECHAT-UIN", randomUin());
        addRouteTag(request);
        return map(execute(request.build(), timeout), json -> {
            int ret = json.path("ret").asInt(0);
            int errcode = json.path("errcode").asInt(0);
            if (ret == -14 || errcode == -14) {
                throw new SessionExpiredException();
            }
            if (ret != 0 || errcode != 0) {
                int protocolCode = ret != 0 ? ret : errcode;
                throw new TransportException(
                        "ILINK-PROTOCOL-003",
                        "服务端协议错误，ret=" + ret + "，errcode=" + errcode,
                        false,
                        null,
                        null, protocolCode, null, path, 0, null);
            }
            return json;
        });
    }

    private CompletionStage<JsonNode> execute(Request request, Duration timeout) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new ClientClosedException("协议客户端"));
        }
        CallFuture<JsonNode> result = new CallFuture<>();
        Call call;
        try {
            call = client.newCall(request);
            call.timeout().timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new TransportException(
                    "ILINK-NET-002", "iLink 网络请求无法创建", true, failure,
                    null, null, null, request.url().encodedPath(), 0, null));
        }
        runningCalls.add(call);
        result.attach(call);
        if (closed.get()) {
            runningCalls.remove(call);
            call.cancel();
            return CompletableFuture.failedFuture(new ClientClosedException("协议客户端"));
        }
        try {
            call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException failure) {
                runningCalls.remove(call);
                if (result.isCancelled()) {
                    return;
                }
                LOGGER.warn("iLink 网络请求失败，method={}，path={}，failureType={}",
                        request.method(), request.url().encodedPath(),
                        failure.getClass().getSimpleName());
                result.completeExceptionally(new TransportException(
                        "ILINK-NET-001", "iLink 网络请求失败", true, failure,
                        null, null, null, request.url().encodedPath(), 0, null));
            }

            @Override
            public void onResponse(Call call, Response response) {
                runningCalls.remove(call);
                try (response; ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        boolean retryable = response.code() == 429 || response.code() >= 500;
                        Duration retryAfter = retryable
                                ? parseRetryAfter(response.header("Retry-After")) : null;
                        LOGGER.warn("iLink HTTP 请求失败，method={}，path={}，status={}，retryable={}",
                                request.method(), request.url().encodedPath(),
                                response.code(), retryable);
                        result.completeExceptionally(new TransportException(
                                "ILINK-HTTP-" + response.code(),
                                "iLink HTTP 请求失败，状态码=" + response.code(),
                                retryable,
                                null,
                                response.code(), null, requestId(response),
                                request.url().encodedPath(), 0, retryAfter));
                        return;
                    }
                    if (body == null) {
                        LOGGER.error("iLink 响应体为空，method={}，path={}",
                                request.method(), request.url().encodedPath());
                        result.completeExceptionally(new TransportException(
                                "ILINK-PROTOCOL-004", "iLink 响应体为空", false, null));
                        return;
                    }
                    result.complete(mapper.readTree(body.byteStream()));
                } catch (Throwable failure) {
                    LOGGER.error("iLink 响应解析失败，method={}，path={}，failureType={}",
                            request.method(), request.url().encodedPath(),
                            failure.getClass().getSimpleName());
                    result.completeExceptionally(new TransportException(
                            "ILINK-PROTOCOL-005", "iLink 响应解析失败", false, failure));
                }
            }
            });
        } catch (RuntimeException failure) {
            runningCalls.remove(call);
            result.completeExceptionally(new TransportException(
                    "ILINK-NET-002", "iLink 网络请求无法进入执行队列", true, failure,
                    null, null, null, request.url().encodedPath(), 0, null));
        }
        return result;
    }

    /** 转换异步结果，并把派生阶段的取消继续传递到源阶段。 */
    private static <T, R> CompletionStage<R> map(
            CompletionStage<T> source, Function<? super T, ? extends R> mapper) {
        CompletableFuture<T> upstream = source.toCompletableFuture();
        CompletableFuture<R> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                if (cancelled) {
                    upstream.cancel(mayInterruptIfRunning);
                }
                return cancelled;
            }
        };
        upstream.whenComplete((value, failure) -> {
            if (failure != null) {
                result.completeExceptionally(unwrap(failure));
                return;
            }
            try {
                result.complete(mapper.apply(value));
            } catch (Throwable mappingFailure) {
                result.completeExceptionally(mappingFailure);
            }
        });
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return Duration.ofSeconds(Math.max(0L, seconds));
        } catch (NumberFormatException ignored) {
            try {
                Instant target = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration delay = Duration.between(clock.instant(), target);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static String requestId(Response response) {
        String requestId = response.header("X-Request-ID");
        return requestId == null ? response.header("Request-ID") : requestId;
    }

    private InboundMessage toInboundMessage(JsonNode node) {
        List<MessageItem> items = new ArrayList<>();
        JsonNode itemNodes = node.path("item_list");
        if (itemNodes.isArray()) {
            for (JsonNode itemNode : itemNodes) {
                Map<String, Object> attributes = mapper.convertValue(
                        itemNode, new TypeReference<Map<String, Object>>() { });
                int type = itemNode.path("type").asInt();
                attributes.remove("type");
                items.add(new MessageItem(type, attributes));
            }
        }
        return new InboundMessage(
                node.path("message_id").asLong(),
                text(node, "from_user_id"),
                text(node, "to_user_id"),
                Instant.ofEpochMilli(node.path("create_time_ms").asLong()),
                text(node, "context_token"),
                items);
    }

    private Map<String, Object> baseInfo() {
        return Map.of("channel_version", channelVersion);
    }

    private void addRouteTag(Request.Builder request) {
        if (routeTag != null && !routeTag.isBlank()) {
            request.header("SKRouteTag", routeTag);
        }
    }

    private static int protocolItemType(SendMessageRequest request) {
        return switch (request.type()) {
            case TEXT -> 1;
            case IMAGE -> 2;
            case VOICE -> 3;
            case FILE -> 4;
            case VIDEO -> 5;
            case COMPOSITE -> throw new IllegalArgumentException("组合消息没有单一消息项类型");
        };
    }

    private static String itemField(SendMessageRequest request) {
        return switch (request.type()) {
            case TEXT -> "text_item";
            case IMAGE -> "image_item";
            case VOICE -> "voice_item";
            case FILE -> "file_item";
            case VIDEO -> "video_item";
            case COMPOSITE -> throw new IllegalArgumentException("组合消息没有单一载荷字段");
        };
    }

    private static List<Map<String, Object>> protocolItems(SendMessageRequest request) {
        if (request.type() != io.github.wxbot.ilink.api.message.OutboundMessageType.COMPOSITE) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", protocolItemType(request));
            item.put(itemField(request), request.payload());
            return List.of(item);
        }
        Object rawItems = request.payload().get("items");
        if (!(rawItems instanceof List<?> values) || values.isEmpty()) {
            throw new IllegalArgumentException("组合消息项不能为空");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof CompositeMessageItem item)) {
                throw new IllegalArgumentException("组合消息项类型无效");
            }
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("type", item.type());
            encoded.put(item.field(), item.payload());
            result.add(encoded);
        }
        return List.copyOf(result);
    }

    private static String randomUin() {
        return Integer.toUnsignedString(UUID.randomUUID().hashCode());
    }

    private static HttpUrl url(URI base, String path) {
        HttpUrl parsed = HttpUrl.get(base);
        return parsed.newBuilder().encodedPath(path).build();
    }

    private static String text(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null || value.isBlank()) {
            throw new TransportException(
                    "ILINK-PROTOCOL-006", "响应缺少必要字段：" + field, false, null);
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "不能为空");
        }
        return value;
    }

    private static URI absolute(URI value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (!value.isAbsolute()) {
            throw new IllegalArgumentException(name + "必须是绝对地址");
        }
        return value;
    }

    private static Duration positiveTimeout(Duration value, String name) {
        Objects.requireNonNull(value, name + "不能为空");
        if (value.isNegative() || value.isZero() || value.toMillis() == 0L) {
            throw new IllegalArgumentException(name + "必须至少为 1 毫秒");
        }
        return value;
    }

    /** 关闭协议对象并取消它自己的在途请求；仅在显式取得所有权时释放底层客户端资源。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        runningCalls.forEach(Call::cancel);
        runningCalls.clear();
        if (ownsClient) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            if (client.cache() != null) {
                try {
                    client.cache().close();
                } catch (IOException ignored) {
                    // 关闭缓存失败不影响其余协议资源继续释放。
                }
            }
        }
        LOGGER.info("iLink HTTP 协议客户端已关闭，ownsClient={}", ownsClient);
    }

    /** 取消 CompletableFuture 时同步取消底层 OkHttp Call。 */
    private static final class CallFuture<T> extends CompletableFuture<T> {
        private volatile Call call;

        private void attach(Call value) {
            this.call = value;
            if (isCancelled()) {
                value.cancel();
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            Call current = call;
            if (cancelled && current != null) {
                current.cancel();
            }
            return cancelled;
        }
    }
}
