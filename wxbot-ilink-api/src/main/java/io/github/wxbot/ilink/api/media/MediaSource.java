/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.api.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * 可以重复打开的媒体数据源。
 *
 * <p>SDK 会关闭 {@link #openStream()} 返回的流，但不会修改或删除来源文件。实现必须确保每次调用返回一个从头
 * 开始的新流，以支持摘要计算和网络重试。
 */
public interface MediaSource {

    /** @return 原始媒体字节数 */
    long contentLength() throws IOException;

    /** @return 从头开始的新输入流 */
    InputStream openStream() throws IOException;

    /** 从文件创建媒体源。 */
    static MediaSource of(Path path) {
        return new MediaSource() {
            @Override
            public long contentLength() throws IOException {
                return Files.size(path);
            }

            @Override
            public InputStream openStream() throws IOException {
                return Files.newInputStream(path);
            }
        };
    }

    /** 从小型字节数组创建媒体源。数组会复制，避免调用方后续修改。 */
    static MediaSource of(byte[] bytes) {
        Objects.requireNonNull(bytes, "媒体字节数组不能为空");
        byte[] copy = bytes.clone();
        return new MediaSource() {
            @Override
            public long contentLength() {
                return copy.length;
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(copy);
            }
        };
    }

    /** 从缓冲区剩余内容创建小型媒体源，不改变调用方缓冲区位置。 */
    static MediaSource of(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "媒体缓冲区不能为空");
        ByteBuffer view = buffer.asReadOnlyBuffer();
        byte[] copy = new byte[view.remaining()];
        view.get(copy);
        return of(copy);
    }

    /**
     * 从可重复打开的输入流工厂创建媒体源。
     *
     * <p>每次调用工厂都必须返回从头开始的新流；SDK 负责关闭返回的流。该形式适合数据库大对象、对象存储或
     * 其他无法直接表示为 {@link Path} 的流式数据源。
     */
    static MediaSource of(long contentLength, InputStreamFactory factory) {
        if (contentLength < 0L) {
            throw new IllegalArgumentException("媒体长度不能为负数");
        }
        Objects.requireNonNull(factory, "输入流工厂不能为空");
        return new MediaSource() {
            @Override
            public long contentLength() {
                return contentLength;
            }

            @Override
            public InputStream openStream() throws IOException {
                return Objects.requireNonNull(factory.open(), "输入流工厂不能返回空值");
            }
        };
    }

    /** 可以抛出 IO 异常的可重复输入流工厂。 */
    @FunctionalInterface
    interface InputStreamFactory {
        /** @return 从头开始的新输入流 */
        InputStream open() throws IOException;
    }
}
