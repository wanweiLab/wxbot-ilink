/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 多 Bot 管理后台启动入口。 */
@SpringBootApplication
public class WxbotILinkAdminApplication {
    /** 启动 Spring Boot 管理服务。 */
    public static void main(String[] args) {
        SpringApplication.run(WxbotILinkAdminApplication.class, args);
    }
}
