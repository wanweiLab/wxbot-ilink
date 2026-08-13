/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.message;

import io.github.wxbot.ilink.api.message.InboundMessage;
import io.github.wxbot.ilink.api.message.MessageDelivery;
import io.github.wxbot.ilink.api.message.StoredMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableMessagePublisherTest {

    private final ReliableMessagePublisher publisher = new ReliableMessagePublisher();

    @AfterEach
    void 关闭发布器() {
        publisher.close();
    }

    @Test
    void 应遵守需求量并等待订阅者确认() throws Exception {
        InMemoryInboxStore inbox = new InMemoryInboxStore(Clock.systemUTC());
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription.set(value);
            }

            @Override
            public void onNext(MessageDelivery item) {
                item.ack();
                delivered.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });
        StripedMessageDispatcher dispatcher = new StripedMessageDispatcher(
                1, 2, inbox, publisher, Duration.ofSeconds(1), 3,
                io.github.wxbot.ilink.api.message.AcknowledgementMode.MANUAL);
        StoredMessage stored = inbox.persistBatch(
                        "client", "", new io.github.wxbot.ilink.api.message.UpdateBatch(
                                "cursor", List.of(message())), Duration.ofMinutes(1))
                .toCompletableFuture().join().acceptedMessages().get(0);

        assertTrue(dispatcher.dispatch(stored));
        Thread.sleep(40L);
        assertEquals(1L, delivered.getCount());
        assertEquals(1L, inbox.countPending("client").toCompletableFuture().join());

        subscription.get().request(1);
        Thread.sleep(1050L);
        StoredMessage retry = inbox.claimPending(
                "client", Instant.now(), 1, Duration.ofMinutes(1))
                .toCompletableFuture().join().get(0);
        assertTrue(dispatcher.dispatch(retry));
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        dispatcher.close();

        assertEquals(0L, inbox.countPending("client").toCompletableFuture().join());
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void 第二个订阅者应被拒绝() {
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        publisher.subscribe(subscriber(null));
        publisher.subscribe(subscriber(rejected));

        assertTrue(rejected.get() instanceof IllegalStateException);
    }

    private Flow.Subscriber<MessageDelivery> subscriber(AtomicReference<Throwable> error) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(MessageDelivery item) {
            }

            @Override
            public void onError(Throwable throwable) {
                if (error != null) {
                    error.set(throwable);
                }
            }

            @Override
            public void onComplete() {
            }
        };
    }

    private static InboundMessage message() {
        return new InboundMessage(1L, "user", "bot", Instant.now(), "token", List.of());
    }
}
