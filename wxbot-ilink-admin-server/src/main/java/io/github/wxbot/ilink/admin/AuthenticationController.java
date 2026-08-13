/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.HttpStatus.NO_CONTENT;

/** 管理后台账号密码登录接口。 */
@RestController
@RequestMapping("/api/auth")
public class AuthenticationController {
    private final AdminSessionService sessions;

    public AuthenticationController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    /** 校验配置文件中的管理员账号密码。 */
    @PostMapping("/login")
    public AdminSessionService.SessionToken login(@RequestBody LoginRequest request) {
        return sessions.login(request.username(), request.password());
    }

    /** 注销当前浏览器会话。 */
    @PostMapping("/logout")
    @ResponseStatus(NO_CONTENT)
    public void logout(HttpServletRequest request) {
        sessions.logout(AdminAuthenticationFilter.bearer(
                request.getHeader(HttpHeaders.AUTHORIZATION)));
    }

    /** @param username 管理员账号 @param password 管理员密码 */
    public record LoginRequest(String username, String password) { }
}
