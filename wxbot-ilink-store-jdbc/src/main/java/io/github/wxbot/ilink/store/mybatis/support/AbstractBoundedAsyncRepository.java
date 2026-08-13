/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.store.mybatis.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 阻塞数据库仓库的有界异步执行基类。
 *
 * <p>统一线程命名、队列上限、拒绝日志和关闭行为，具体仓库只负责 Mapper 调用与领域异常转换。
 */
public abstract class AbstractBoundedAsyncRepository implements AutoCloseable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ThreadPoolExecutor workers;

    /** 创建固定大小、有界队列的数据库执行器。 */
    protected AbstractBoundedAsyncRepository(
            String threadName, int workerCount, int queueCapacity) {
        if (workerCount <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("数据库工作线程数和队列容量必须大于零");
        }
        workers = new ThreadPoolExecutor(
                workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** 提交阻塞数据库任务，并把队列拒绝转换为具体领域异常。 */
    protected final <T> CompletionStage<T> submit(
            Supplier<T> action,
            Function<RejectedExecutionException, ? extends RuntimeException> rejectionMapper) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            logger.warn("数据库异步任务被拒绝，active={}，queued={}",
                    workers.getActiveCount(), workers.getQueue().size());
            result.completeExceptionally(rejectionMapper.apply(failure));
        }
        return result;
    }

    /** 停止接收新任务；已提交任务继续完成。 */
    @Override
    public void close() {
        workers.shutdown();
        logger.info("数据库异步仓库已关闭");
    }
}
