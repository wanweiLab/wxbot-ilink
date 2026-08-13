/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.media;

import io.github.wxbot.ilink.api.media.MediaDigest;
import io.github.wxbot.ilink.api.media.MediaSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreamingMediaCryptoTest {

    @Test
    void 应流式完成加密和解密() throws Exception {
        byte[] plain = new byte[1024 * 1024 + 3];
        Arrays.fill(plain, (byte) 7);
        byte[] key = "0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();

        long inputLength = StreamingMediaCrypto.encrypt(
                new ByteArrayInputStream(plain), encrypted, key);
        ByteArrayOutputStream decrypted = new ByteArrayOutputStream();
        long outputLength = StreamingMediaCrypto.decrypt(
                new ByteArrayInputStream(encrypted.toByteArray()), decrypted, key);

        assertEquals(plain.length, inputLength);
        assertEquals(plain.length, outputLength);
        assertEquals(StreamingMediaCrypto.encryptedLength(plain.length), encrypted.size());
        assertArrayEquals(plain, decrypted.toByteArray());
    }

    @Test
    void 应计算原始摘要和填充后长度() throws Exception {
        MediaDigest digest = StreamingMediaCrypto.digest(MediaSource.of("abc".getBytes()));

        assertEquals(3L, digest.rawLength());
        assertEquals(16L, digest.encryptedLength());
        assertEquals("900150983cd24fb0d6963f7d28e17f72", digest.md5Hex());
    }

    @Test
    void 完整分组也应增加一个填充分组() {
        assertEquals(16L, StreamingMediaCrypto.encryptedLength(0));
        assertEquals(32L, StreamingMediaCrypto.encryptedLength(16));
    }

    @Test
    void 应支持缓冲区和可重复输入流工厂且不改变原缓冲区位置() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap("prefix-media".getBytes());
        buffer.position(7);

        MediaDigest fromBuffer = StreamingMediaCrypto.digest(MediaSource.of(buffer));
        MediaDigest fromFactory = StreamingMediaCrypto.digest(MediaSource.of(
                5L, () -> new ByteArrayInputStream("media".getBytes())));

        assertEquals(7, buffer.position());
        assertEquals(5L, fromBuffer.rawLength());
        assertEquals(fromBuffer.md5Hex(), fromFactory.md5Hex());
    }
}
