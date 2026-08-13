# wxbot-ilink

`wxbot-ilink` 是一个基于 Java 17 的非官方微信 iLink Bot SDK，面向需要长期运行、可恢复、可观测和多 Bot 管理的 Java 应用。

> 本项目与腾讯、微信不存在隶属、授权或背书关系。微信及相关标识归其权利人所有。使用前请自行确认协议、账号和业务合规风险。

## 项目解决什么问题

它把一次 Bot 接入中容易混在一起的工作拆成了稳定的运行时能力：

- 二维码登录和已有会话恢复；
- iLink HTTP 协议认证、消息长轮询、消息发送、输入态和媒体上传授权；
- cursor 与消息 inbox 的原子保存、去重、确认、重试和死信；
- 同一用户消息有序、不同用户并行，以及队列满时的背压；
- `context_token` 的上下文管理和 `client_id` 的发送幂等；
- AES-GCM 会话快照、可靠收件箱、数据库租约和多实例单活；
- 流式媒体加解密、上传、下载、长度和摘要校验；
- 客户端状态机、重试预算、熔断、健康快照、指标和追踪扩展点；
- Spring Boot 基础设施自动配置、Reactor 门面、TestKit 和多 Bot 管理后台。

SDK 对外提供 at-least-once 消息语义，不承诺 exactly-once。业务处理有副作用时，必须使用 `messageId` 或业务唯一键实现幂等。

## 核心设计

项目可以按四层理解：

```text
业务代码 / 管理后台
        ↓
公共 API 与应用服务
        ↓
Bot 客户端运行时：状态机、拉取器、分发器、发送器、重试
        ↓
HTTP 协议适配器、媒体实现、文件/JDBC 存储、指标适配
```

- `api` 定义公共契约，不依赖 HTTP、数据库或 Spring。
- `core` 实现 Bot 生命周期、可靠消息和发送逻辑。
- `http-okhttp` 只负责把协议接口映射成真实 HTTP/JSON 请求。
- `store-jdbc` 负责持久化和事务边界，生产快照和消息内容使用 AES-GCM 加密。
- `manager` 负责一用户一 Bot、运行时隔离和多实例租约。
- `admin-server` 只负责 REST 入参出参和 Spring Boot 装配，不直接访问 Mapper。

最重要的运行规则：

1. 每个 Bot 只有一个有效消息 poller，避免 cursor 竞争。
2. 消息和 cursor 先持久化，再交给业务处理器。
3. 同一个用户的消息按固定分片串行，不同用户可以并行。
4. 发送自动重试时复用同一个 `client_id`。
5. `bot_token`、`context_token` 和快照不会写入普通日志。
6. 所有队列、线程、请求、重试和关闭等待都有上限。

## 快速使用

普通 Java 应用至少需要 `wxbot-ilink-api`、`wxbot-ilink-core` 和 `wxbot-ilink-http-okhttp`。
生产环境建议再加入 `wxbot-ilink-store-jdbc`，并使用持久化快照、inbox 和租约。

```java
OkHttpClient http = new OkHttpClient.Builder().build();

try (ILinkOkHttpProtocol protocol = new ILinkOkHttpProtocol(http);
     WxbotILinkClient client = WxbotILinkClient.builder()
             .clientKey("production-bot-1")
             .protocols(protocol, protocol, protocol)
             .stateStore(stateStore)
             .inboxStore(inboxStore)
             .leaseStore(inboxStore)
             .leaseOwnerId(System.getenv("POD_UID"))
             .messageHandler(delivery -> handle(delivery.message())
                     .thenCompose(ignored -> delivery.ack()))
             .build()) {

    boolean restored = client.restore().toCompletableFuture().join();
    if (!restored) {
        LoginAttempt attempt = client.login().toCompletableFuture().join();
        showQrCode(attempt.qrCode().imageContent());
        attempt.completion().toCompletableFuture().join();
    }

    client.send(SendMessageRequest.text(
            UUID.randomUUID().toString(), targetUserId, "你好"));
}
```

首次运行使用 `login()` 获取二维码；后续启动优先使用 `restore()`。生产环境不要使用内存存储作为恢复方案，主密钥必须通过环境注入、KMS 或其他密钥管理系统提供。

## 消息处理

配置 `messageHandler` 后，处理器返回成功阶段时 SDK 自动确认消息；失败时会根据配置安排重试，超过上限后进入死信。也可以使用 `client.messages()` 订阅可靠投递，但每条消息都必须调用 `ack()` 或 `retry()`。

```java
delivery -> businessProcess(delivery.message())
        .thenCompose(ignored -> delivery.ack())
```

消息可能重复投递，因此业务数据库写入应使用消息 ID 或业务幂等键去重。慢处理器不会运行在 HTTP 回调线程中；队列饱和时，SDK 会暂停继续拉取而不是无限增加内存队列。

## 发送消息

```java
SendMessageRequest request = SendMessageRequest.text(
        UUID.randomUUID().toString(),
        targetUserId,
        "处理完成");

CompletionStage<SendReceipt> receipt = client.send(request);
```

默认使用目标会话的最新上下文。需要时可以使用 `ContextReference.explicit(token)` 或 `ContextReference.fromMessage(messageId)` 指定上下文。`clientId` 在超时或网络错误后的重试中必须保持不变，避免重复发送。

## 模块

| 模块 | 用途 |
| --- | --- |
| `wxbot-ilink-api` | 公共模型、接口、SPI 和异常 |
| `wxbot-ilink-core` | 生命周期、状态机、消息拉取、分发、发送和重试 |
| `wxbot-ilink-http-okhttp` | OkHttp + Jackson 的 iLink HTTP 协议实现 |
| `wxbot-ilink-media` | 流式媒体上传、下载和 AES 加解密 |
| `wxbot-ilink-store-jdbc` | 加密快照、inbox、cursor 和租约 |
| `wxbot-ilink-manager` | 一用户一 Bot 和多 Bot 运行时管理 |
| `wxbot-ilink-observability` | Micrometer 指标适配 |
| `wxbot-ilink-reactor` | Reactor `Mono` 异步门面 |
| `wxbot-ilink-spring-boot-starter` | Spring Boot HTTP/指标自动配置 |
| `wxbot-ilink-testkit` | 不访问真实网络的脚本协议和长稳测试工具 |
| `wxbot-ilink-examples` | Java 17 接入示例 |
| `wxbot-ilink-admin-server` | MySQL 多 Bot REST 后台和可执行服务 |
| `wxbot-ilink-admin-web` | React + TypeScript 管理端源码 |

## 管理后台

`wxbot-ilink-admin-server` 提供账号登录、用户绑定、二维码登录状态查询、会话恢复、停止、解绑和绑定身份测试消息等接口。

主要流程：

```text
绑定业务 userId
    ↓
生成二维码
    ↓
微信扫码并确认
    ↓
加密保存 Bot 会话快照
    ↓
后续启动自动恢复
```

数据库中不应写入真实密码或主密钥。管理服务所需的 MySQL 地址、账号、密码、AES 主密钥、管理员账号密码和实例 ID 都必须由部署环境注入。

前端源码位于 `wxbot-ilink-admin-web`。生产静态资源应由前端构建生成，不将本地构建缓存、IDE 配置或运行时密钥提交到仓库。

## 构建和测试

要求：

- JDK 17；
- Maven 3.8.6 或更高版本；
- 管理端前端开发需要 Node.js 和 npm。

构建 Java 模块：

```bash
mvn clean verify
```

构建管理端前端：

```bash
cd wxbot-ilink-admin-web
npm install
npm run build
```

协议和媒体测试使用本机随机端口，不访问公网。真实 iLink 网络、MySQL 多实例、断网恢复和长时间稳定性需要在目标部署环境单独验收。

## 开发资料

代码仓库根目录只保留面向使用者的本 README；本地工作区中的开发进度、架构决策、设计审计、迁移、发布和验收资料统一放在 `super-wx/docs/wxbot-ilink/`，不作为 SDK 仓库的发布入口：

```text
super-wx/docs/wxbot-ilink/
```

这些资料用于本地开发和维护，不属于 SDK 发布包的源码入口。源码阅读建议从 `wxbot-ilink-api` 的 `ILinkClient` 开始，再依次阅读 `WxbotILinkClient`、`UpdateLoop`、`UpdatePoller`、`StripedMessageDispatcher`、`MessageSender` 和 `BotRuntimeManager`。

## 许可证

项目使用 Apache License 2.0。分发时请同时阅读 `LICENSE`、`NOTICE` 和第三方依赖许可证说明。
