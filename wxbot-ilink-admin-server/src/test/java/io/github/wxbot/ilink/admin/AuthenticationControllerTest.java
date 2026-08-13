/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 管理员账号密码和 Bearer 会话测试。 */
class AuthenticationControllerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AdminProperties properties = new AdminProperties();
        properties.setUsername("admin-user");
        properties.setPassword("strong-password");
        AdminSessionService sessions = new AdminSessionService(properties);
        mvc = MockMvcBuilders.standaloneSetup(new AuthenticationController(sessions))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new AdminAuthenticationFilter(sessions))
                .build();
    }

    @Test
    void 正确账号密码可以获得短时令牌() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin-user\",\"password\":\"strong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void 错误密码不会签发令牌() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin-user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void 受保护接口拒绝无令牌请求() throws Exception {
        mvc.perform(get("/api/users/bots"))
                .andExpect(status().isUnauthorized());
    }
}
