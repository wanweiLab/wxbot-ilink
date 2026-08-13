/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.reactor;

import io.github.wxbot.ilink.api.ILinkClient;
import io.github.wxbot.ilink.api.login.LoginAttempt;
import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.message.SendReceipt;
import io.github.wxbot.ilink.api.observability.ClientHealth;
import io.github.wxbot.ilink.api.session.ClientSnapshot;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateListener;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactorILinkClientTest {

    @Test
    void 应把初始需求传递给JdkFlow订阅() {
        ControlledPublisher publisher = new ControlledPublisher();
        AtomicReference<MessageDelivery> received = new AtomicReference<>();
        new ReactorILinkClient(new StubClient(publisher)).messages().subscribe(new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(org.reactivestreams.Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(MessageDelivery value) {
                received.set(value);
            }
        });

        assertEquals(1L, publisher.requested.get());
        MessageDelivery delivery = new StubDelivery();
        publisher.emit(delivery);
        assertEquals(delivery, received.get());
    }

    @Test
    void 应在Reactor取消时取消底层订阅() {
        ControlledPublisher publisher = new ControlledPublisher();
        BaseSubscriber<MessageDelivery> subscriber = new BaseSubscriber<>() {
        };
        new ReactorILinkClient(new StubClient(publisher)).messages().subscribe(subscriber);

        subscriber.dispose();

        assertTrue(publisher.cancelled.get());
    }

    private static final class ControlledPublisher implements Flow.Publisher<MessageDelivery> {
        private final AtomicLong requested = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Flow.Subscriber<? super MessageDelivery> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super MessageDelivery> value) {
            subscriber = value;
            value.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long demand) {
                    requested.getAndAccumulate(demand, ReactorILinkClientTest::addCap);
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        }

        private void emit(MessageDelivery delivery) {
            subscriber.onNext(delivery);
        }
    }

    private static final class StubDelivery implements MessageDelivery {
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        @Override
        public InboundMessage message() {
            return new InboundMessage(1L, "user", "bot", Instant.EPOCH, "context", List.of());
        }

        @Override
        public int attempt() {
            return 1;
        }

        @Override
        public CompletionStage<Void> completion() {
            return completion;
        }

        @Override
        public CompletionStage<Void> ack() {
            completion.complete(null);
            return completion;
        }

        @Override
        public CompletionStage<Void> retry(Throwable cause) {
            completion.complete(null);
            return completion;
        }
    }

    private record StubClient(Flow.Publisher<MessageDelivery> messages) implements ILinkClient {
        @Override
        public ClientState state() {
            return ClientState.NEW;
        }

        @Override
        public ClientHealth health() {
            return new ClientHealth(ClientState.NEW, Instant.EPOCH, null, 0, null, 0, 0, null);
        }

        @Override
        public void addStateListener(ClientStateListener listener) {
        }

        @Override
        public CompletionStage<LoginAttempt> login() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<Boolean> restore() {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<SendReceipt> send(SendMessageRequest request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public CompletionStage<SendReceipt> sendWithTyping(
                SendMessageRequest request, java.time.Duration typingDuration) {
            return send(request);
        }

        @Override
        public CompletionStage<Optional<ClientSnapshot>> saveSnapshot() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public void close() {
        }
    }

    private static long addCap(long left, long right) {
        long sum = left + right;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }
}
