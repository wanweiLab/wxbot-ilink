/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api;

import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.BatchSendResult;
import io.github.wxbot.ilink.api.observability.ClientHealth;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateListener;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * wxbot-ilink 客户端公共门面。
 *
 * <p>客户端线程安全。登录、恢复、发送和快照操作均使用异步契约；{@link #close()} 会停止新任务并按照配置期限
 * 回收内部资源。关闭后的客户端不能重新使用。
 */
public interface ILinkClient extends AutoCloseable {

    /** @return 当前生命周期状态 */
    ClientState state();

    /** @return 当前客户端的只读健康快照 */
    ClientHealth health();

    /** 注册状态监听器。 */
    void addStateListener(ClientStateListener listener);

    /**
     * 返回可靠消息订阅入口。
     *
     * <p>当前实现只允许一个消费订阅者，订阅者必须为每条消息调用 ack 或 retry。使用构建器配置
     * {@code messageHandler} 时，该处理器会占用此订阅入口。
     *
     * @return 遵守订阅需求量的消息发布器
     */
    Flow.Publisher<MessageDelivery> messages();

    /** 启动二维码登录。 */
    CompletionStage<LoginAttempt> login();

    /**
     * 从配置的状态存储恢复客户端。
     *
     * @return 找到并成功恢复快照时返回 {@code true}
     */
    CompletionStage<Boolean> restore();

    /** 发送消息。 */
    CompletionStage<SendReceipt> send(SendMessageRequest request);

    /**
     * 按顺序发送多条协议请求并返回可检查的部分成功结果。
     *
     * <p>任一请求失败后不再发送后续请求；失败作为结果返回，不以异常结束整个阶段。
     */
    default CompletionStage<BatchSendResult> sendSequential(List<SendMessageRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("顺序发送请求不能为空"));
        }
        List<SendMessageRequest> immutable = List.copyOf(requests);
        List<SendReceipt> receipts = new ArrayList<>();
        return sendNext(immutable, receipts, 0);
    }

    /**
     * 在调用线程等待消息发送完成。
     *
     * @param request 发送请求
     * @param timeout 最长等待时间
     * @return 发送回执
     */
    default SendReceipt sendBlocking(SendMessageRequest request, Duration timeout) {
        return await(send(request), timeout);
    }

    /** 带输入态发送消息。 */
    CompletionStage<SendReceipt> sendWithTyping(
            SendMessageRequest request, Duration typingDuration);

    /**
     * 在调用线程等待带输入态的发送完成。
     *
     * @param request 发送请求
     * @param typingDuration 输入态持续时间
     * @param timeout 最长等待时间
     * @return 发送回执
     */
    default SendReceipt sendWithTypingBlocking(
            SendMessageRequest request, Duration typingDuration, Duration timeout) {
        return await(sendWithTyping(request, typingDuration), timeout);
    }

    /** 导出并保存当前恢复快照。 */
    CompletionStage<Optional<ClientSnapshot>> saveSnapshot();

    /** 停止客户端并释放资源。该方法幂等。 */
    @Override
    void close();

    private static <T> T await(CompletionStage<T> stage, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("同步等待超时必须大于零");
        }
        try {
            return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("同步等待被中断", failure);
        } catch (java.util.concurrent.TimeoutException failure) {
            throw new IllegalStateException("同步等待超时", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("异步操作执行失败", cause);
        }
    }

    private CompletionStage<BatchSendResult> sendNext(
            List<SendMessageRequest> requests, List<SendReceipt> receipts, int index) {
        if (index == requests.size()) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new BatchSendResult(receipts, -1, null));
        }
        return send(requests.get(index)).handle((receipt, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure;
                return java.util.concurrent.CompletableFuture.completedFuture(
                        new BatchSendResult(receipts, index, cause));
            }
            receipts.add(receipt);
            return sendNext(requests, receipts, index + 1);
        }).thenCompose(stage -> stage);
    }
}
