/*
 * Copyright 2026 wxbot-ilink contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.wxbot.ilink.examples;

import io.github.wxbot.ilink.api.config.ILinkClientConfig;
import io.github.wxbot.ilink.api.exception.ILinkException;
import io.github.wxbot.ilink.core.WxbotILinkClient;
import io.github.wxbot.ilink.core.message.InMemoryInboxStore;
import io.github.wxbot.ilink.core.session.InMemoryStateStore;
import io.github.wxbot.ilink.http.okhttp.ILinkOkHttpProtocol;
import okhttp3.OkHttpClient;

import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;

/**
 * 最小二维码登录示例。
 *
 * <p>本示例为便于阅读使用内存存储，重启会丢失恢复状态；生产环境应替换为 JDBC 存储，并妥善保管主密钥。
 */
public final class BasicBotExample {
    private BasicBotExample() {
    }

    public static void main(String[] args) throws InterruptedException {
        Clock clock = Clock.systemUTC();
        OkHttpClient http = new OkHttpClient();
        try (ILinkOkHttpProtocol protocol = new ILinkOkHttpProtocol(http);
             WxbotILinkClient client = WxbotILinkClient.builder()
                     .clientKey("example-bot")
                     .config(ILinkClientConfig.builder().build())
                     .protocols(protocol, protocol, protocol)
                     .stateStore(new InMemoryStateStore())
                     .inboxStore(new InMemoryInboxStore(clock))
                     .messageHandler(delivery -> {
                         System.out.println("收到消息：" + delivery.message());
                         return delivery.ack();
                     })
                     .clock(clock)
                     .build()) {
            var attempt = client.login().toCompletableFuture().join();
            System.out.println("请扫描二维码内容：" + attempt.qrCode().imageContent());
            try {
              var  BotSession = attempt.completion().toCompletableFuture().join();
                System.out.println("登录成功，客户端开始接收消息。"+BotSession);
            } catch (CompletionException failure) {
                printLoginFailure(failure.getCause() == null ? failure : failure.getCause());
                throw failure;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(client::close, "示例关闭线程"));
            new CountDownLatch(1).await();
        }
    }

    private static void printLoginFailure(Throwable failure) {
        if (failure instanceof ILinkException ilinkFailure) {
            System.err.printf("登录失败：%s，错误码=%s，可重试=%s%n",
                    ilinkFailure.getMessage(), ilinkFailure.errorCode(), ilinkFailure.retryable());
        } else {
            System.err.println("登录失败：" + failure.getMessage());
        }
        System.err.println("请检查网络、代理和防火墙设置；网络恢复后可重新运行示例获取二维码。");
    }
}
