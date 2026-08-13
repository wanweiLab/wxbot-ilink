/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.reactor;

import io.github.wxbot.ilink.api.ILinkClient;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将核心异步契约转换为 Reactor {@link Mono} 的轻量门面。
 *
 * <p>每次订阅才调用底层客户端，取消订阅不会关闭共享客户端。生命周期仍由调用方管理。
 */
public final class ReactorILinkClient {
    private final ILinkClient delegate;

    public ReactorILinkClient(ILinkClient delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "客户端不能为空");
    }

    public Mono<LoginAttempt> login() {
        return Mono.defer(() -> Mono.fromCompletionStage(delegate.login()));
    }

    public Mono<Boolean> restore() {
        return Mono.defer(() -> Mono.fromCompletionStage(delegate.restore()));
    }

    public Mono<SendReceipt> send(SendMessageRequest request) {
        return Mono.defer(() -> Mono.fromCompletionStage(delegate.send(request)));
    }

    public Mono<SendReceipt> sendWithTyping(SendMessageRequest request, Duration duration) {
        return Mono.defer(() -> Mono.fromCompletionStage(delegate.sendWithTyping(request, duration)));
    }

    public Mono<Optional<ClientSnapshot>> saveSnapshot() {
        return Mono.defer(() -> Mono.fromCompletionStage(delegate.saveSnapshot()));
    }

    /**
     * 将核心可靠消息发布器转换为带背压的 Reactor 流。
     *
     * @return 每条元素仍需显式 ack 或 retry 的消息流
     */
    public Flux<MessageDelivery> messages() {
        return Flux.create(sink -> {
            AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
            AtomicBoolean terminated = new AtomicBoolean();
            sink.onRequest(demand -> {
                Flow.Subscription current = subscription.get();
                if (current != null && !terminated.get()) {
                    current.request(demand);
                }
            });
            sink.onDispose(() -> {
                terminated.set(true);
                Flow.Subscription current = subscription.get();
                if (current != null) {
                    current.cancel();
                }
            });
            delegate.messages().subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription value) {
                    if (!subscription.compareAndSet(null, value)) {
                        value.cancel();
                        return;
                    }
                    if (terminated.get()) {
                        value.cancel();
                        return;
                    }
                    long pendingDemand = sink.requestedFromDownstream();
                    if (pendingDemand > 0L) {
                        value.request(pendingDemand);
                    }
                }

                @Override
                public void onNext(MessageDelivery delivery) {
                    sink.next(delivery);
                }

                @Override
                public void onError(Throwable failure) {
                    sink.error(failure);
                }

                @Override
                public void onComplete() {
                    sink.complete();
                }
            });
        }, FluxSink.OverflowStrategy.ERROR);
    }
}
