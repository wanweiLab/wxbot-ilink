/* Copyright 2026 wxbot-ilink contributors; SPDX-License-Identifier: Apache-2.0 */
package io.github.wxbot.ilink.testkit;

import io.github.wxbot.ilink.api.message.ContextReference;
import io.github.wxbot.ilink.api.message.SendMessageRequest;
import io.github.wxbot.ilink.api.session.BotSession;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptedILinkProtocolTest {
    @Test
    void shouldCaptureSentRequests() {
        BotSession session = new BotSession("token", "u", "b", URI.create("https://example.test"));
        ScriptedILinkProtocol protocol = new ScriptedILinkProtocol(session, Clock.systemUTC());
        protocol.send(session, new SendMessageRequest("id", "u1",
                io.github.wxbot.ilink.api.message.OutboundMessageType.TEXT,
                ContextReference.explicit("ctx"), Map.of("text", "你好")))
                .toCompletableFuture().join();
        assertEquals("id", protocol.sentRequests().get(0).clientId());
    }
}
