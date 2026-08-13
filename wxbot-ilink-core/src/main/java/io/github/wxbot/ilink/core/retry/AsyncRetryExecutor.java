/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.retry;

import io.github.wxbot.ilink.api.exception.CircuitOpenException;
import io.github.wxbot.ilink.api.exception.RetryBudgetExceededException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** 不阻塞调用线程的异步重试执行器。 */
public final class AsyncRetryExecutor {

    private final RetryPolicy policy;
    private final ScheduledExecutorService scheduler;
    private final RetryBudget retryBudget;
    private final CircuitBreaker circuitBreaker;

    public AsyncRetryExecutor(RetryPolicy policy, ScheduledExecutorService scheduler) {
        this(policy, scheduler, null, null);
    }

    /** 创建带共享重试预算和熔断器的执行器。 */
    public AsyncRetryExecutor(
            RetryPolicy policy,
            ScheduledExecutorService scheduler,
            RetryBudget retryBudget,
            CircuitBreaker circuitBreaker) {
        this.policy = Objects.requireNonNull(policy, "重试策略不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度器不能为空");
        this.retryBudget = retryBudget;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * 执行可重试异步操作。
     *
     * @param operation 每次调用必须创建一次新的异步操作
     */
    public <T> CompletionStage<T> execute(Supplier<CompletionStage<T>> operation) {
        RetryFuture<T> result = new RetryFuture<>();
        if (retryBudget != null) {
            retryBudget.recordRequest();
        }
        attempt(operation, result, 1);
        return result;
    }

    private <T> void attempt(
            Supplier<CompletionStage<T>> operation,
            RetryFuture<T> result,
            int attemptNumber) {
        if (result.isDone()) {
            return;
        }
        if (circuitBreaker != null && !circuitBreaker.tryAcquirePermission()) {
            result.completeExceptionally(new CircuitOpenException());
            return;
        }
        CompletableFuture<T> current;
        try {
            current = Objects.requireNonNull(operation.get(), "异步操作不能返回空阶段")
                    .toCompletableFuture();
            result.attachCurrent(current);
        } catch (Throwable failure) {
            if (circuitBreaker != null) {
                circuitBreaker.recordFailure();
            }
            onFailure(operation, result, attemptNumber, failure);
            return;
        }
        current.whenComplete((value, failure) -> {
            result.detachCurrent(current);
            if (result.isDone()) {
                return;
            }
            if (failure == null) {
                if (circuitBreaker != null) {
                    circuitBreaker.recordSuccess();
                }
                result.complete(value);
            } else {
                if (circuitBreaker != null) {
                    circuitBreaker.recordFailure();
                }
                onFailure(operation, result, attemptNumber, unwrap(failure));
            }
        });
    }

    private <T> void onFailure(
            Supplier<CompletionStage<T>> operation,
            RetryFuture<T> result,
            int attemptNumber,
            Throwable failure) {
        if (!policy.shouldRetry(failure, attemptNumber)) {
            result.completeExceptionally(failure);
            return;
        }
        if (retryBudget != null && !retryBudget.tryAcquireRetry()) {
            result.completeExceptionally(new RetryBudgetExceededException(failure));
            return;
        }
        Duration delay = policy.nextDelay(failure, attemptNumber);
        try {
            ScheduledFuture<?> scheduled = scheduler.schedule(
                    () -> attempt(operation, result, attemptNumber + 1),
                    delay.toMillis(), TimeUnit.MILLISECONDS);
            result.attachScheduled(scheduled);
        } catch (RejectedExecutionException rejected) {
            // 调度器关闭意味着所属客户端正在退出，保留原始业务失败更利于定位。
            failure.addSuppressed(rejected);
            result.completeExceptionally(failure);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause() : failure;
    }

    /** 取消结果时同步取消当前协议阶段和尚未开始的重试任务。 */
    private static final class RetryFuture<T> extends CompletableFuture<T> {
        private final AtomicReference<CompletableFuture<?>> current = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();

        private void attachCurrent(CompletableFuture<?> value) {
            current.set(value);
            if (isCancelled()) {
                value.cancel(true);
            }
        }

        private void detachCurrent(CompletableFuture<?> value) {
            current.compareAndSet(value, null);
        }

        private void attachScheduled(ScheduledFuture<?> value) {
            ScheduledFuture<?> previous = scheduled.getAndSet(value);
            if (previous != null && previous != value && !previous.isDone()) {
                previous.cancel(false);
            }
            if (isCancelled()) {
                value.cancel(false);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                CompletableFuture<?> active = current.getAndSet(null);
                if (active != null) {
                    active.cancel(mayInterruptIfRunning);
                }
                ScheduledFuture<?> waiting = scheduled.getAndSet(null);
                if (waiting != null) {
                    waiting.cancel(false);
                }
            }
            return cancelled;
        }
    }
}
