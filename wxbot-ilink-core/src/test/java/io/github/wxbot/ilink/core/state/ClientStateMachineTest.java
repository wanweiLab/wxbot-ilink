/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.state;

import io.github.wxbot.ilink.api.exception.IllegalStateTransitionException;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.api.state.ClientStateChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientStateMachineTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void 应按照合法路径完成登录() {
        ClientStateMachine machine = new ClientStateMachine(FIXED_CLOCK, failure -> { });
        List<ClientStateChangedEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        machine.transitionTo(ClientState.LOGIN_REQUIRED, "没有恢复快照");
        machine.transitionTo(ClientState.QR_WAITING, "二维码已生成");
        machine.transitionTo(ClientState.QR_SCANNED, "用户已扫码");
        machine.transitionTo(ClientState.CONNECTED, "登录确认成功");

        assertEquals(ClientState.CONNECTED, machine.current());
        assertEquals(4, events.size());
        assertEquals(1, events.get(0).sequence());
        assertEquals(4, events.get(3).sequence());
        assertEquals(Instant.parse("2026-08-12T00:00:00Z"), events.get(3).occurredAt());
    }

    @Test
    void 应拒绝跳过登录过程直接连接() {
        ClientStateMachine machine = new ClientStateMachine(FIXED_CLOCK, failure -> { });

        IllegalStateTransitionException error = assertThrows(
                IllegalStateTransitionException.class,
                () -> machine.transitionTo(ClientState.CONNECTED, "错误地跳过登录"));

        assertEquals(ClientState.NEW, error.from());
        assertEquals(ClientState.CONNECTED, error.to());
        assertEquals(ClientState.NEW, machine.current());
    }

    @Test
    void 相同状态的重复转换应保持幂等() {
        ClientStateMachine machine = new ClientStateMachine(FIXED_CLOCK, failure -> { });
        List<ClientStateChangedEvent> events = new ArrayList<>();
        machine.addListener(events::add);

        machine.transitionTo(ClientState.LOGIN_REQUIRED, "没有恢复快照");
        ClientStateChangedEvent duplicate =
                machine.transitionTo(ClientState.LOGIN_REQUIRED, "重复检查登录状态");

        assertNull(duplicate);
        assertEquals(1, events.size());
    }

    @Test
    void 单个监听器失败不应阻止其他监听器() {
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<ClientStateChangedEvent> received = new CopyOnWriteArrayList<>();
        ClientStateMachine machine = new ClientStateMachine(FIXED_CLOCK, failures::add);
        machine.addListener(event -> {
            throw new IllegalStateException("模拟监听器失败");
        });
        machine.addListener(received::add);

        machine.transitionTo(ClientState.LOGIN_REQUIRED, "没有恢复快照");

        assertEquals(1, failures.size());
        assertEquals(1, received.size());
    }

    @Test
    void 关闭后不允许重新启动() {
        ClientStateMachine machine = new ClientStateMachine(FIXED_CLOCK, failure -> { });
        machine.transitionTo(ClientState.CLOSING, "主动关闭空闲客户端");
        machine.transitionTo(ClientState.CLOSED, "资源释放完成");

        assertThrows(IllegalStateTransitionException.class,
                () -> machine.transitionTo(ClientState.LOGIN_REQUIRED, "尝试重新启动"));
    }
}
