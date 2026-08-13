/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.media;

import io.github.wxbot.ilink.api.media.MediaDigest;
import io.github.wxbot.ilink.api.media.MediaSource;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 与 iLink 媒体协议兼容的流式 AES/ECB/PKCS7 工具。
 *
 * <p>JCE 中的 {@code PKCS5Padding} 对 AES 分组实际执行 PKCS7 兼容填充。方法使用固定大小缓冲区，不会把完整
 * 原文或密文保存在堆中。调用方拥有输入和输出流，方法不会主动关闭它们。
 */
public final class StreamingMediaCrypto {

    private static final int AES_BLOCK_BYTES = 16;
    private static final int BUFFER_BYTES = 64 * 1024;

    private StreamingMediaCrypto() {
    }

    /** 计算摘要和可预知的密文长度。 */
    public static MediaDigest digest(MediaSource source) throws IOException {
        MessageDigest md5 = md5();
        long length = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = source.openStream()) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkInterrupted();
                if (read == 0) {
                    continue;
                }
                md5.update(buffer, 0, read);
                length += read;
            }
        }
        if (length != source.contentLength()) {
            throw new IOException("媒体长度在读取期间发生变化");
        }
        return new MediaDigest(length, encryptedLength(length), HexFormat.of().formatHex(md5.digest()));
    }

    /**
     * 将原始媒体流式加密到输出流。
     *
     * @return 写入的原始字节数
     */
    public static long encrypt(InputStream plain, OutputStream encrypted, byte[] key)
            throws IOException {
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key);
        long copied;
        // CipherOutputStream 必须完成 close 才会写入最终填充分组，因此使用不关闭底层流的包装器。
        try (CipherOutputStream cipherOutput = new CipherOutputStream(
                new NonClosingOutputStream(encrypted), cipher)) {
            copied = copy(plain, cipherOutput);
        }
        return copied;
    }

    /**
     * 将加密媒体流式解密到输出流。
     *
     * @return 写入的原始字节数
     */
    public static long decrypt(InputStream encrypted, OutputStream plain, byte[] key)
            throws IOException {
        Cipher cipher = cipher(Cipher.DECRYPT_MODE, key);
        try (CipherInputStream cipherInput = new CipherInputStream(
                new NonClosingInputStream(encrypted), cipher)) {
            return copy(cipherInput, plain);
        }
    }

    /** 计算 AES PKCS7 填充后的密文长度。 */
    public static long encryptedLength(long plainLength) {
        if (plainLength < 0) {
            throw new IllegalArgumentException("原始长度不能为负数");
        }
        return Math.multiplyExact(Math.addExact(plainLength / AES_BLOCK_BYTES, 1L), AES_BLOCK_BYTES);
    }

    private static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            checkInterrupted();
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("媒体流处理已经取消");
        }
    }

    private static Cipher cipher(int mode, byte[] key) {
        if (key == null || key.length != AES_BLOCK_BYTES) {
            throw new IllegalArgumentException("AES-128 密钥必须是 16 字节");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"));
            return cipher;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("当前 Java 运行时不支持媒体加解密算法", failure);
        }
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前 Java 运行时不支持 MD5", failure);
        }
    }

    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;

        private NonClosingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.flush();
        }
    }

    private static final class NonClosingInputStream extends InputStream {
        private final InputStream delegate;

        private NonClosingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            return delegate.read(bytes, offset, length);
        }
    }
}
