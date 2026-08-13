/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 完整 Spring Boot 装配、静态前端和认证边界测试。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-app;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "wxbot.admin.master-key-base64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "wxbot.admin.username=admin",
        "wxbot.admin.password=test-password",
        "wxbot.admin.initialize-schema=true",
        "wxbot.admin.restore-on-startup=false"
})
@AutoConfigureMockMvc
class WxbotILinkAdminApplicationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private OkHttpClient httpClient;

    @Test
    void 首页包含构建后的Arco管理前端() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/assets/arco-")));
    }

    @Test
    void 完整上下文可以登录且保护Bot接口() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(get("/api/users/bots"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 后台共享Http客户端允许完整长轮询周期() {
        assertEquals(35_000, httpClient.readTimeoutMillis());
        assertEquals(20_000, httpClient.writeTimeoutMillis());
    }
}
