# 阶段 04：Realtime Proxy Checklist

说明：本清单在阶段收口时逐项执行并记录实际结果。勾选表示已经以最终代码和可重复证据验证，不表示“代码看起来存在”。任一未明确标注为非阻塞的失败都会阻止阶段通过。

## 1. 构建与技术基线

- [ ] JDK 为 21，未启用 Preview Feature 或虚拟线程。
- [ ] Spring Boot/Cloud/Alibaba、Nacos、Redis、RocketMQ 与固定基线一致。
- [ ] 依赖树只有 RocketMQ 5.x gRPC Client，无旧 Remoting Client 冲突。
- [ ] Realtime standalone profile 不连接 Redis、MQ、Nacos 或 Auction。
- [ ] 根目录 `mvn clean verify` 全部模块通过，失败/错误/跳过均为 0。
- [x] 前端 lint、类型检查、全部测试和生产构建通过。（2026-09-25：`pnpm lint`、`pnpm type-check`、`pnpm test` 通过，20 个测试文件/56 项测试全通过，`pnpm build-only` 成功。）
- [ ] Git diff 无空白错误，仓库不包含 target、dist、node_modules 或运行产物。

  实际结果：2026-09-24 停止应用进程后执行 `mvn clean verify`，11 个模块 BUILD SUCCESS；前端 `pnpm lint`、`pnpm type-check`、`pnpm test`（20 个文件/56 项测试）和 `pnpm build-only` 均通过。Maven 测试仍有按 profile 跳过的集成项，故本节“失败/错误/跳过均为 0”暂不勾选。

## 2. Migration 与旧数据升级

- [ ] V5 及后续 migration 从空 Auction Schema 执行成功。
- [ ] 从真实 V4 Schema 原地升级成功，已有拍品、场次、报名、报价和 Outbox 保留。
- [ ] 重复 migrate 执行数为 0，不修改已执行的 V1～V4。
- [ ] historical bid_record 正确回填 MANUAL，不伪造代理规则或延时次数。
- [ ] originalEndAt、endAt、extensionCount 约束有效。
- [ ] proxy、command 和 bid source 的唯一键、金额、状态、版本、索引有效。
- [ ] Auction 应用用户仍只能访问自己的 Schema，Realtime 没有 Auction DB 凭证。

实际结果：待执行。

## 3. WebSocket Ticket 与握手安全

- [ ] 只有有效 JWT 能签发 ticket。
- [ ] ticket 使用安全随机数，原文不保存到 Redis、数据库或日志。
- [ ] ticket TTL 为配置值且默认 30 秒。
- [ ] 同一 ticket 首次握手成功，第二次重放失败。
- [ ] 过期、畸形、未知 ticket 均拒绝 upgrade。
- [ ] ticket 签发达到用户/IP 限额时返回统一 429。
- [ ] Redis 不可用时 ticket 签发返回统一 503，不降级为匿名。
- [x] 允许的 localhost/127.0.0.1 Origin 可连接，未知/null Origin 被拒绝。（2026-09-22：`RealtimeWebSocketHandshakeInterceptorTest` 与 `RealtimeWebSocketPropertiesTest` 通过。）
- [x] Gateway 剥离伪造身份头，Realtime 只信任 ticket 身份。（2026-09-22：`GatewayAuthenticationWebFilterTest`、`RealtimeWebSocketHandshakeInterceptorTest` 通过；Gateway WebSocket route 使用 ticket 交给 Realtime 消费。）
- [x] JWT、ticket、内部 Token 不出现在 URL 之外的可持久日志、错误、指标或关闭原因；日志不打印含 ticket 的完整请求目标。（2026-09-22：ticket 摘要存储、脱敏 toString、拒绝响应和 Gateway Authorization 清理测试通过；本批未新增敏感日志。）

  实际结果：本批 WebSocket ticket/Origin/Gateway 安全项已完成；连接租约、订阅、背压和整栈故障演练留待后续任务。

- [x] 每用户连接租约、单连接订阅上限、客户端消息大小和控制帧速率限制有效。（2026-09-22：`RealtimeWebSocketHandlerTest` 覆盖 PING/PONG、21 个订阅触发限制、9KB 消息以 1009 关闭；连接租约使用 Redis Lua sorted-set 清理过期成员并原子限制并发。）

## 4. 协议、限制与会话生命周期

- [x] CONNECTED 包含 connectionId、serverTime 和心跳参数。（2026-09-25：Realtime WebSocket handler 定向测试通过，`RealtimeConnected` 契约校验覆盖三字段。）
- [ ] SUBSCRIBE/UNSUBSCRIBE 重放幂等，非法 auctionId 返回可恢复 ERROR。
- [ ] PING/PONG 正确回显时间且不改变订阅。
- [ ] 未知 type、未知 protocolVersion、缺字段、畸形 JSON 和超过 8 KiB 消息被拒绝。
- [ ] 单连接最多 20 个订阅，超限不影响已有订阅。
- [ ] 单用户最多 5 个连接，异常断开后租约会过期回收。
- [ ] 控制消息速率限制有效，不允许客户端高频占用 CPU。
- [ ] 90 秒空闲连接关闭；正常心跳连接保持。
- [ ] 应用优雅停止拒绝新会话并给现有客户端可恢复关闭语义。

实际结果：2026-09-25 Realtime handler、Gateway 和前端状态机定向测试通过；剩余项主要是浏览器真实 Console/Network 观察和优雅停止的人工验证。

## 5. RocketMQ 消费与 Redis 多实例扇出

- [x] Realtime 使用独立 `tidebid-realtime-auction-v1` Consumer Group。（2026-09-22：Realtime gRPC PushConsumer 配置固定该 Consumer Group；本批同时纳入 RocketMQ bootstrap 和 topology check。）
- [x] 正确消费 BidAccepted、AuctionTimeExtended、ClosedSold 和 ClosedUnsold。（2026-09-22：统一 EventMessageDecoder 注册四类事件，handler 逐类转换为 Realtime contract。）
- [x] 只有解码及 Redis Lua 原子“eventId 幂等 + publish”成功后才 ACK，不存在 SET 成功但未 publish 的窗口。（2026-09-22：Lua 在一个脚本中执行 EXISTS、SET PX 和 PUBLISH；transport 仅在 handler 返回后 SUCCESS。）
- [x] Redis publish 失败时 MQ 重试，同一 eventId 恢复后只产生一次有效 fanout。（2026-09-22：Redis 异常向 handler 抛出，消费结果 FAILURE；publisher 单测覆盖首次 1/重复 0。）
- [x] 重复 MQ 消息、重复 Pub/Sub 消息不会导致客户端重复报价。（2026-09-22：eventId 幂等标记和本地 session 广播测试通过。）
- [x] 两个 Realtime 实例分别持有连接时，都能收到同一事件。（2026-09-23：两个直连 WebSocket 分别连接 9104/9204，收到同一 `BID_ACCEPTED` eventId/sequence。）
- [ ] 没有相关订阅的实例不会创建无界 auction 缓存。
- [x] 公共消息不包含 bidderId、winnerId、代理最高价或消息原始正文。（2026-09-22：session registry 只构造 RealtimeBidAccepted/RealtimeAuctionClosed 等公开 contract，广播测试确认不含 bidderId。）
- [x] RocketMQ Broker 恢复后 backlog 可追平，没有静默丢失或永久 DEAD。（2026-09-24：`smoke.ps1 -RealtimeBrokerRecovery` 在 Broker 停止期间提交报价，恢复后同一 WebSocket 收到对应 `BID_ACCEPTED`；后续 sequence、报价历史和拓扑检查均通过。）

实际结果：本批 RocketMQ/Redis 事件消费、本地扇出和 Consumer Group 初始化/检查已完成；拓扑检查脚本在当前本地 Broker 上通过。两个 Realtime 实例真实联调已通过，Broker/Redis 故障恢复和 backlog 追平留待整栈验收。

## 6. Snapshot 与断线恢复

- [x] Realtime 不直连 Auction Schema，只通过受保护内部接口读取 snapshot。（2026-09-22：Realtime 仅调用 AuctionSnapshotClient/Feign，未引入 Auction 数据库依赖。）
- [x] 内部接口不经 Gateway 暴露，缺失/错误内部 Token 被拒绝。（2026-09-22：Auction 内部 snapshot endpoint 使用内部 Token 过滤器，Gateway 无对应 route。）
- [x] snapshot 返回当前状态、价格、最低价、endAt、extensionCount 和 lastSequenceNo。（2026-09-22：RealtimeSnapshot 契约和 Feign 映射已覆盖完整公开状态。）
- [x] afterSequenceNo 只返回更大的 sequence，按升序且最多 100 条。（2026-09-22：Auction snapshot API 强制 afterSequenceNo、升序和 100 条上限。）
- [x] 订阅先进入 SYNCING 缓冲，SNAPSHOT 先于同步期间的新事件发送。（2026-09-22：session registry 在同步状态下暂存事件，并在同一 session 锁内先发送 SNAPSHOT。）
- [x] snapshot 与缓冲事件重叠时按 sequence/eventId 去重。（2026-09-22：已补充 sequence 重叠和 eventId 重复测试；快照已有 bid sequence 不重复下发。）
- [x] 缺口超过上限、缓冲溢出或响应不一致时发送 RESYNC_REQUIRED。（2026-09-22：服务端验证 lastSequenceNo、100 条增量上限、首尾 sequence 和 auctionId；分别覆盖 `HISTORY_GAP`、`BUFFER_OVERFLOW`、`SNAPSHOT_INCONSISTENT`，定向测试通过。）
- [ ] 浏览器短线重连后价格、报价次数、历史、结束时间和终态与 MySQL 一致。
- [ ] Realtime 完全重启后不依赖本地内存，也能从 snapshot 恢复。

实际结果：2026-09-22 已完成内部 snapshot client、订阅 SYNCING 缓冲、Snapshot 优先发送、sequence/eventId 重叠去重、服务端断线恢复基线校验，以及浏览器页面隐藏/恢复和刷新游标恢复；Realtime 定向测试通过，前端客户端测试覆盖暂停、恢复和最新 `lastSequenceNo`。双实例重启恢复和整栈故障演练仍待后续批次。

## 7. 背压与故障隔离

- [x] 同一 WebSocket session 的发送严格串行。（2026-09-22：独立发送队列只有一个 drain 任务持有发送顺序。）
- [x] 正常连接消息顺序与协议规则一致。（2026-09-22：CONNECTED/SNAPSHOT/补发事件和广播均通过同一队列；相关 fake session 测试通过。）
- [x] 发送队列最多 128 条，不随客户端变慢无限增长。（2026-09-22：ArrayBlockingQueue 有界，容量绑定 `queue.send-capacity`，默认 128。）
- [x] 队列溢出发送 RESYNC_REQUIRED/1013，客户端能重新同步。（2026-09-22：溢出时发送 `BUFFER_OVERFLOW` 恢复提示并关闭 1013；客户端可用 lastSequenceNo 重新 SUBSCRIBE。）
- [x] 一个慢消费者不会阻塞 Redis listener、MQ listener 或其他连接。（2026-09-22：每个 session 独立队列，广播线程只执行非阻塞 offer。）
- [x] session 关闭、发送异常和广播并发不会产生重复关闭或资源泄漏。（2026-09-22：队列 close 幂等；移除 session 时释放队列；完整 Realtime 测试通过。）
- [x] 连接、订阅、同步缓冲和 Redis 租约在正常/异常断开后释放。（2026-09-22：关闭回调移除 registry 状态并释放 lease；心跳协调器处理过期租约。）

实际结果：2026-09-22 已完成发送背压、1013 恢复提示、心跳调度、空闲关闭和租约续期代码；Realtime 全量测试 40 项通过，真实 Redis 租约续期与双实例故障演练待整栈验收。

## 8. 代理竞价规则

- [ ] 只有非卖家、REGISTERED 用户可在 OPEN 且未到期场次设置代理。
- [ ] 首个代理不会直接把展示价抬到 maxAmount。
- [ ] 第二高上限推动展示价到 `min(最高上限, 第二高上限 + increment)`。
- [ ] 同最高价由较早优先指令领先，展示价达到该上限。
- [ ] 展示价永不下降、不超过领先 maxAmount，sequence 连续无缺口。
- [ ] 手动报价触发代理反击时 HTTP 明确返回当前用户未领先和最终快照。
- [ ] 单命令产生 0、1 或 2 条报价时都能以相同 requestId 幂等重放。
- [ ] 相同 requestId 不同 payload 返回 409，不改变规则、价格或 Outbox。
- [ ] 代理提高、降低、停用均符合规则；已接受报价不会撤回。
- [ ] 两个代理不会通过消息递归无限互相加价。
- [ ] 并发手动/代理请求由 MySQL CAS 产生唯一合法顺序和最终领先者。
- [ ] 场次关闭后代理不能继续执行或改变终态。
- [ ] 他人 API、历史、WebSocket、日志和指标均看不到 maxAmount。

实际结果：2026-09-25 Auction 代理 API 定向测试通过；`RealtimeProxyDemo`/`RealtimeProxyPaymentDemo` 已覆盖代理提升、公开价、sequence、历史和支付终态。迁移/MySQL profile 测试在本次定向命令中跳过 12 项，未将其误标为通过。

## 9. 反狙击延时

- [ ] 窗口外报价不延时。
- [ ] 窗口内成功价格变化把 endAt 推进到公式结果。
- [ ] 仅修改代理 max、失败请求、幂等重放和 CAS 失败不延时。
- [ ] 多次触发后 endAt 不超过 originalEndAt + 300 秒。
- [ ] endAt、extensionCount、报价、BidAccepted、AuctionTimeExtended 和新 CloseCommand 同事务。
- [ ] 任一 Outbox/报价写入失败时 endAt 不部分更新。
- [ ] 旧 expectedEndAt 命令不会提前关拍。
- [ ] 新命令、旧命令、数据库扫描和多个 Auction 实例只产生一个终态。
- [x] 截止边界的报价/关拍竞态只有“报价先提交并延时”或“关拍先提交并拒绝报价”两种合法结果。（2026-09-21：真实 MySQL `AuctionProxyMySqlIntegrationTest` 通过；5 项测试全部通过。）
- [ ] WebSocket 实时更新倒计时，断线恢复后以 MySQL endAt 为准。

实际结果：截止边界竞态、旧命令、重复关拍、数据库扫描和多轮延时已完成真实 MySQL/领域测试；本阶段其余 Realtime、API 和整栈验收仍待执行。

## 10. API、Gateway 与权限

- [x] 代理 GET 只返回本人规则；其他用户和卖家无法查询。（2026-09-25：`AuctionProxyBidControllerTest` 和服务层权限测试通过。）
- [x] 代理 PUT/DELETE 与手动报价都要求合法 X-Request-Id。（2026-09-25：代理 Controller 定向测试覆盖 PUT/DELETE 请求头与 400 边界。）
- [x] 大整数 ID、两位小数金额和 UTC 时间保持既有线协议。（2026-09-25：Auction Proxy Controller/Realtime contract 定向测试和代理实时 smoke 覆盖 64 位 ID、金额精度与 UTC 时间。）
- [x] Realtime HTTP 和 WebSocket 只能通过 Gateway 对前端提供。（2026-09-25：`GatewayRouteConfigurationTest` 验证 HTTP `lb://` 与 WebSocket `lb:ws://` 路由。）
- [x] `/api/realtime/**` 使用正常 JWT；`/ws/**` 只接受一次性 ticket。（2026-09-22：Gateway 与 Realtime 定向测试通过。）
- [x] 内部 snapshot、MQ 和 Redis 端点不在 Gateway route 中。（2026-09-25：Gateway `/internal/realtime/auctions/1/snapshot` 返回 404；直连 Auction 内部 Snapshot 无 Token 返回 401；Gateway route 定向测试通过。）
- [x] 无认证、越权、业务冲突和基础设施故障返回统一稳定错误。（2026-09-25：Gateway/Realtime/Auction 定向测试覆盖 401/403/404/409/503，错误响应不包含下游堆栈或内部地址。）
- [x] CORS/WebSocket Origin 没有扩大为通配符。（2026-09-25：Gateway CORS 与 Realtime Origin 白名单测试通过，仅允许 localhost/127.0.0.1:5173。）
- [x] Actuator 仍只暴露 health/info。（2026-09-25：Gateway 与 Realtime 应用测试验证 health/info 可用，`/actuator`、`env`、`beans` 不暴露。）

实际结果：2026-09-25 Gateway 定向测试 21 项、Realtime 定向测试 18 项通过；路由、JWT/ticket、Actuator 暴露边界和代理 API 请求头已验证。内部端点负向探测、全量大整数/错误响应审查仍待收口。

## 11. Vue 实时演示

- [x] 页面准确展示连接中、实时、恢复中和离线状态。（2026-09-22：AuctionDetailView 状态标签由客户端状态机驱动，离线保留手动重连和 HTTP 刷新。）
- [x] 在线时新报价无需手动刷新即可更新价格、最低价、次数和历史。（2026-09-22：BID_ACCEPTED 事件直接更新详情和最近报价列表。）
- [x] 重复/乱序消息不会造成价格回退、历史重复或计数错误。（2026-09-22：客户端按 auctionId/sequenceNo 去重并对 gap 重新订阅；Vitest 覆盖重复报价。）
- [x] 倒计时使用服务端时间校正，反狙击后立即显示新 endAt。（2026-09-22：AUCTION_EXTENDED 使用服务端 endAt 更新详情；倒计时展示沿用服务端 UTC 时间。）
- [x] 本人可设置、修改、停用代理并看到自己的 maxAmount。（2026-09-22：详情页本人代理面板接入 GET/PUT/DELETE，测试覆盖启用和停用。）
- [x] 其他登录用户看不到代理 maxAmount 或是否仍有剩余额度。（2026-09-22：maxAmount 只来自本人接口并只渲染在本人代理控制区；测试确认公开文本不出现最高价。）
- [x] 被代理超过、当前领先、规则已停用和终态文案明确。（2026-09-22：代理面板按 `leading`/status 展示状态，终态沿用成交/流拍结果。）
- [x] 收到关闭事件后按钮禁用并出现成交/流拍与订单入口。（2026-09-25：前端终态 Vitest 与 `RealtimeProxyPaymentDemo` 的 `AUCTION_CLOSED`/订单入口整栈链路通过。）
- [ ] 离线时 HTTP 手动/代理操作的可用性和风险提示准确，不伪装为实时。
- [ ] 有限指数退避不会无限快速重连，达到上限可手动恢复。
- [x] 刷新页面、切后台、断网恢复和 Realtime 重启后均恢复正确状态。（2026-09-25：前端状态机测试、`RealtimeRestartRecovery` 和 `RealtimeProxyPaymentDemo` Snapshot 恢复均通过。）
- [ ] 浏览器 Console 无错误，Network 中 HTTP 经 5173→9000，WebSocket 经 Gateway upgrade。
- [ ] 页面在常见桌面宽度无明显遮挡、跳动或无法操作区域。

实际结果：2026-09-22 已完成前端 ticket/WebSocket 客户端、连接状态机、有限重连、快照/报价/延时/终态应用、本人代理控制表单，以及页面隐藏/恢复和刷新游标恢复；前端 56 项测试、lint、type-check 和生产构建通过。双浏览器整栈演示、Realtime 重启和中间件故障恢复待后续批次。

补充：`scripts/smoke.ps1 -RealtimeProxy` 已提供 Gateway ticket、WebSocket upgrade、CONNECTED 和 SNAPSHOT 的可重复验收入口；真实整栈执行仍属于第 13/14 节阻塞验收。

## 12. 可观测性与秘密

- [ ] 可观察当前连接、订阅、同步、推送、重复、重试、慢消费者和延迟。
- [x] 指标不以 userId、auctionId、eventId、connectionId 为 tag。（2026-09-25：`RealtimeMetricsTest` 通过，指标仅使用固定 outcome 标签；连接/订阅使用无标签 Gauge。）
- [ ] 日志可用 connectionId、auctionId、eventId、traceId 串联关键路径。
- [x] 日志不包含 JWT、ticket、内部 Token、AccessKey、OSS 签名、代理 maxAmount 或完整消息正文。（2026-09-25：`audit-runtime-logs.ps1` 审计最新 14 个日志文件通过；仓库高置信秘密扫描通过，未发现 AK/Signed URL/私钥/Bearer 样本。）
- [ ] Redis ticket、连接租约和事件幂等 key 均有 TTL。
- [ ] 错误响应和 WebSocket ERROR 不包含堆栈、SQL、内部地址或下游正文。

实际结果：2026-09-25 已完成最新运行日志审计和仓库高置信秘密扫描；连接/订阅运行态观测、Nacos/Redis 样本和错误响应最终审查仍待阶段收口。

## 13. 整栈故障演练

阶段 04 本批工具验证（2026-09-23）：`realtime-instance.ps1 -Action Start` 在 9204 启动成功，
`check-nacos-registrations.ps1 -ExpectedRealtimeInstances 2` 检查到 9104/9204 两个健康实例，
`smoke.ps1 -RealtimeProxy` 通过，停止后等待 Nacos 租约回收恢复单实例基线。两个独立浏览器
连接跨实例收取同一事件、Redis/Broker 故障恢复和完整端到端演示仍未完成。

- [x] 从空应用进程和已停止中间件冷启动，保留全部命名卷和历史业务数据。（2026-09-25：先执行 `stop-apps.ps1`、`infra-down.ps1`，再执行 `infra-up.ps1`、`start-apps.ps1 -SkipBuild`；中间件、应用和历史卷恢复正常，未执行 `docker compose down -v`。）
- [x] 七个应用健康，六个 Java 服务在 Nacos 注册唯一且端口正确。（2026-09-25：`status.ps1 -AssertHealthy` 和 `check-nacos-registrations.ps1 -ExpectedRealtimeInstances 1` 通过，Gateway/Account/Auction/Trade/Realtime/AI 均注册健康且端口正确。）
- [x] 两个浏览器连接到不同 Realtime 实例时仍收到相同实时事件。（2026-09-23：`smoke.ps1 -RealtimeMultiInstance` 以两个直连 WebSocket 等价验证 9104/9204，均收到相同 `BID_ACCEPTED.eventId`/sequence；两个实例使用同一 RocketMQ Consumer Group + Redis Pub/Sub。）
- [x] 停 Broker 后报价与 Outbox 提交成功；恢复后实时/快照追平。（2026-09-24：`smoke.ps1 -RealtimeBrokerRecovery` 验证 Broker 停止期间报价提交成功，恢复后原 WebSocket 连接收到挂起 `BID_ACCEPTED`；后续报价、历史和 RocketMQ topology 校验通过。）
- [x] 停 Redis 后新 ticket 失败且 MQ 不提前 ACK；恢复后消费和连接正常。（2026-09-23：Redis 停止时 ticket 明确返回 503；恢复后 Redis healthy，Realtime smoke 通过。）
- [x] 停一个 Realtime 实例后另一实例连接不受影响，原连接可重连恢复。（2026-09-24：`smoke.ps1 -RealtimeRestartRecovery` 验证 9204 停止期间 9104 收到报价，9204 重启后新连接 Snapshot 收敛。）
- [x] 停全部 Realtime 时 Auction 报价、延时、关拍、订单和支付继续正确。（2026-09-25：`app-outage.ps1 -Service realtime -Action Suspend` 后运行完整 `smoke.ps1 -ReliableTrade`，成交支付、流拍释放和支付超时补偿全部通过；Realtime 恢复后 Nacos 注册和 `RealtimeProxyTradeDemo` Snapshot/关拍/订单入口继续通过。）
- [ ] 人工制造重复 MQ、乱序 Pub/Sub 和 sequence gap，页面最终与 MySQL 一致。
- [x] 慢客户端被关闭，正常客户端仍持续接收。（2026-09-24：Realtime 定向测试验证慢连接队列溢出后只发送 `RESYNC_REQUIRED(BUFFER_OVERFLOW)` 并关闭 1013，健康连接未被关闭且仍发送消息。）
- [x] 重启整栈后代理规则、动态 endAt、报价和终态保持一致。（2026-09-25：冷启动后 `smoke.ps1 -RealtimeProxyPaymentDemo` 重新验证代理 sequence 1～5、反狙击、CLOSED_SOLD、支付和结算。）
- [x] 全程未执行 `docker compose down -v` 或删除用户数据卷。（2026-09-25：本批仅执行 `infra-down.ps1`/`infra-up.ps1`，脚本输出明确保留容器和命名卷。）

实际结果：2026-09-25 已完成停应用/停中间件后的冷启动，Nacos 注册、完整可靠交易和实时支付场景均通过；“停全部 Realtime”和人工乱序注入仍未执行。

## 14. 端到端烟雾

- [ ] 卖家创建送审，两个买家报名并在两个浏览器打开同一场次。
- [x] 买家 A 设置较高代理 max，展示价没有直接泄露 max。（2026-09-24：`smoke.ps1 -RealtimeProxyDemo` 验证 A 设置 150.00 后首个公开展示价仍为 100.00，公开事件只包含展示金额。）
- [x] 买家 B 手动报价，A 自动以最小必要金额反击，两端实时一致。（2026-09-24：B 出价 110.00 后，A 以 120.00 自动反击；两个独立 WebSocket 收到相同 eventId，sequence 连续。）
- [x] 买家 B 设置更高代理并成为领先者，sequence/历史/实时事件一致。（2026-09-25：`smoke.ps1 -RealtimeProxyDemo` 在 B 重连后设置 200.00，B 以最小必要公开价 160.00 领先；A/B 收到相同 `BID_ACCEPTED` eventId，sequence 5，MySQL 报价历史最新记录与 sequence/金额一致，公开事件不含 `maxAmount`；Outbox 可靠投递由阶段 03/故障 smoke 单独覆盖。）
- [x] 临近结束报价触发反狙击，两端倒计时和 endAt 同步更新。（2026-09-24：45 秒场次在开场后进入 60 秒反狙击窗口；A 首次代理报价响应 `extended=true`，两端均收到 `AUCTION_EXTENDED`。）
- [x] 一端断网跨过若干报价，恢复后通过 snapshot 补齐且无重复。（2026-09-24：B 断线期间 A 继续产生新 sequence，B 使用旧 `lastSequenceNo` 重连，Snapshot 恢复到最新 sequence 4。）
- [x] 达到最终 endAt 后唯一关拍，WebSocket 显示正确赢家和成交价。（2026-09-25：`smoke.ps1 -RealtimeProxyTradeDemo` 等待 `CLOSED_SOLD`，两端均收到 `AUCTION_CLOSED`，B 被标记为赢家，成交价为 160.00；HTTP 终态和报价历史一致。）
- [x] 阶段 03 的赢家保证金、订单、支付和卖家入账继续完成。（2026-09-24：`-ReliableTrade` 全量烟雾通过 sold/payment、unsold/release、payment-timeout/forfeit/credit。）
- [x] 代理实时场景的订单支付和卖家结算继续完成。（2026-09-25：`smoke.ps1 -RealtimeProxyPaymentDemo` 支付 110.00，重复请求返回同一 paymentAttemptId，订单进入 `PAID/COMPLETED`，卖家入账 160.00，赢家/落败者钱包余额符合保证金和尾款变化。）
- [x] 同一 HTTP 请求和同一 MQ 事件重放后价格、sequence、规则、订单和资金不变。（2026-09-25：注册、报名、手动报价、代理命令和支付请求重放均返回原结果；Realtime eventId Redis 幂等和重复 MQ/Pub/Sub 定向测试通过。）
- [x] 烟雾脚本再次运行使用新用户/拍品并完整通过；失败时非零退出且不输出秘密。（2026-09-25：冷启动后 `-ReliableTrade` 和 `-RealtimeProxyPaymentDemo` 均使用新用户/拍品完整通过；输出未包含凭证、Token 或完整签名 URL。）

实际结果：2026-09-25 冷启动后已通过 `smoke.ps1 -ReliableTrade` 和 `smoke.ps1 -RealtimeProxyPaymentDemo`，覆盖最终关拍、双端 `AUCTION_CLOSED`、支付幂等和 `PAID/COMPLETED` 结算；前端开发入口 HTTP 200，Chrome headless DOM 检查确认 Vue 应用挂载并渲染 TideBid 文本。真实浏览器 Console/布局/Network 交互仍待人工验收。

## 15. 数据保留、文档与最终结论

- [ ] 阶段 01～03 的用户、钱包、拍品、报名、报价、订单和资金流水均保留。
- [ ] README 与最终 WebSocket 拓扑、代理算法、反狙击、启动和排错一致。
- [ ] Git、历史、Nacos、日志和运行产物不包含真实秘密或代理最高价。
- [ ] 所有 `tasks.md` 阻塞任务逐项完成并记录证据。
- [ ] 所有本清单阻塞项通过，遗留项均有明确非阻塞判断。
- [ ] 未提前实现 AI 推理、MCP/RAG、真实支付、退款、物流或其他范围外能力。

### 整阶段结论

| 检查项 | 结果 | 证据或备注 |
| --- | --- | --- |
| Migration 与旧数据升级 | 待验收 | |
| Ticket 与 WebSocket 安全 | 待验收 | |
| MQ 消费与 Redis 多实例扇出 | 待验收 | |
| Snapshot 与断线恢复 | 待验收 | |
| 代理竞价与并发 | 待验收 | |
| 反狙击延时与可靠关拍 | 待验收 | |
| Gateway、API 与权限 | 待验收 | |
| Vue 实时演示 | 待验收 | |
| 故障演练与端到端烟雾 | 待验收 | |
| 构建、秘密与阶段边界 | 待验收 | |

最终结论：`待验收`

遗留问题：待阶段实施后填写。

进入阶段 05 的条件：所有阻塞项通过，最终结论为“通过”，并且实时链路故障不会影响 MySQL 最终裁决、可靠关拍、订单或资金一致性。
