/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.lifecycle;

import io.github.wxbot.ilink.api.session.LeaseStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseGuardTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void 关闭调度器() {
        scheduler.shutdownNow();
    }

    @Test
    void 未取得租约时不应启动续约() throws Exception {
        RecordingLeaseStore store = new RecordingLeaseStore(false, true);
        AtomicBoolean lost = new AtomicBoolean();
        LeaseGuard guard = guard(store, () -> lost.set(true));

        assertFalse(guard.acquire().toCompletableFuture().join());
        Thread.sleep(80L);

        assertFalse(guard.isHolding());
        assertEquals(0, store.renewCount.get());
        assertFalse(lost.get());
        guard.close();
    }

    @Test
    void 续约被拒绝时应立即报告失租() throws Exception {
        RecordingLeaseStore store = new RecordingLeaseStore(true, false);
        CountDownLatch lost = new CountDownLatch(1);
        LeaseGuard guard = guard(store, lost::countDown);

        assertTrue(guard.acquire().toCompletableFuture().join());
        assertTrue(lost.await(1, TimeUnit.SECONDS));

        assertFalse(guard.isHolding());
        assertTrue(store.renewCount.get() >= 1);
        guard.close();
    }

    @Test
    void 关闭时应释放已持有租约() {
        RecordingLeaseStore store = new RecordingLeaseStore(true, true);
        LeaseGuard guard = guard(store, () -> { });
        assertTrue(guard.acquire().toCompletableFuture().join());

        guard.closeAsync().toCompletableFuture().join();

        assertTrue(store.released.get());
        assertFalse(guard.isHolding());
    }

    @Test
    void 无法安排首次续约时不应宣称取得租约() {
        RecordingLeaseStore store = new RecordingLeaseStore(true, true);
        scheduler.shutdownNow();
        LeaseGuard guard = guard(store, () -> { });

        assertFalse(guard.acquire().toCompletableFuture().join());
        assertFalse(guard.isHolding());
        assertTrue(store.released.get());
        guard.close();
    }

    private LeaseGuard guard(RecordingLeaseStore store, Runnable lost) {
        return new LeaseGuard(
                store,
                "client",
                "node-1",
                Duration.ofMillis(200),
                Duration.ofMillis(30),
                scheduler,
                Clock.systemUTC(),
                lost,
                io.github.wxbot.ilink.api.observability.MetricsSink.noop());
    }

    private static final class RecordingLeaseStore implements LeaseStore {
        private final boolean acquireResult;
        private final boolean renewResult;
        private final AtomicInteger renewCount = new AtomicInteger();
        private final AtomicBoolean released = new AtomicBoolean();

        private RecordingLeaseStore(boolean acquireResult, boolean renewResult) {
            this.acquireResult = acquireResult;
            this.renewResult = renewResult;
        }

        @Override
        public CompletionStage<Boolean> tryAcquire(
                String clientKey, String ownerId, Instant now, Duration ttl) {
            return CompletableFuture.completedFuture(acquireResult);
        }

        @Override
        public CompletionStage<Boolean> renew(
                String clientKey, String ownerId, Instant now, Duration ttl) {
            renewCount.incrementAndGet();
            return CompletableFuture.completedFuture(renewResult);
        }

        @Override
        public CompletionStage<Void> release(String clientKey, String ownerId) {
            released.set(true);
            return CompletableFuture.completedFuture(null);
        }
    }
}
