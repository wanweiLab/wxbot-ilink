/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.core.lifecycle;

import io.github.wxbot.ilink.api.exception.TransportException;
import io.github.wxbot.ilink.api.exception.SessionExpiredException;
import io.github.wxbot.ilink.api.state.ClientState;
import io.github.wxbot.ilink.core.state.ClientStateMachine;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionSupervisorTest {

    @Test
    void 连续失败应依次进入降级和重连状态() {
        ClientStateMachine machine = connectedMachine();
        ConnectionSupervisor supervisor = new ConnectionSupervisor(machine, 2, 3, Clock.systemUTC());
        TransportException failure = new TransportException(
                "ILINK-NET-001", "临时失败", true, null);

        supervisor.recordFailure(failure);
        assertEquals(ClientState.CONNECTED, machine.current());
        supervisor.recordFailure(failure);
        assertEquals(ClientState.DEGRADED, machine.current());
        supervisor.recordFailure(failure);
        assertEquals(ClientState.RECONNECTING, machine.current());

        supervisor.recordSuccess();
        assertEquals(ClientState.CONNECTED, machine.current());
        assertEquals(0, supervisor.consecutiveFailures());
    }

    @Test
    void 不可恢复错误应直接使会话过期() {
        ClientStateMachine machine = connectedMachine();
        ConnectionSupervisor supervisor = new ConnectionSupervisor(machine, 2, 3, Clock.systemUTC());

        supervisor.recordFailure(new SessionExpiredException());

        assertEquals(ClientState.EXPIRED, machine.current());
    }

    @Test
    void 普通不可重试错误不应误判为会话过期() {
        ClientStateMachine machine = connectedMachine();
        ConnectionSupervisor supervisor = new ConnectionSupervisor(machine, 2, 3, Clock.systemUTC());

        supervisor.recordFailure(new TransportException(
                "ILINK-PROTOCOL-001", "请求参数错误", false, null));

        assertEquals(ClientState.CONNECTED, machine.current());
        assertEquals(0, supervisor.consecutiveFailures());
    }

    private static ClientStateMachine connectedMachine() {
        ClientStateMachine machine = new ClientStateMachine();
        machine.transitionTo(ClientState.LOGIN_REQUIRED, "无恢复快照");
        machine.transitionTo(ClientState.QR_WAITING, "二维码已生成");
        machine.transitionTo(ClientState.QR_SCANNED, "二维码已扫描");
        machine.transitionTo(ClientState.CONNECTED, "登录成功");
        return machine;
    }
}
