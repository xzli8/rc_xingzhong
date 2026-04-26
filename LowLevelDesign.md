# 1. 目标

本文档给出[HighLevelDesign.md](HighLevelDesign.md)的可编码实现方案，并与 HLD 的“先调度架构决策、再模块职责、再扩展点”结构保持一致。
当前 LLD 以 HLD 的方案A（DB 状态机 + 周期扫描）为实现基线。

# 2. 实现范围与架构基线

## 2.1 当前实现范围（MVP）

- 采用 `delivery_task` 作为任务事实来源（source of truth）；
- 调度器周期扫描 DB 执行投递；
- 使用 at-least-once 语义，允许重复投递；
- 实现失败重试、死信和人工重投；
- 保留重试策略接口，默认固定间隔实现。

## 2.2 暂不实现（放到演进阶段）

- CDC(binlog) + MQ 发布/消费链路；
- 多实例强并发抢占与分区调度；
- 按供应商维度限流/熔断；
- 更复杂的错误分类退避。

# 3. 代码分层与职责

- `controller`：对外 API、参数存在性校验、响应构建；
- `service`：业务编排、状态机调度、重试与死信处理；
- `repository`：JPA Repository 与实体持久化；
- `model`：DTO、实体、状态枚举、投递结果模型；
- `config`：基础配置（如 SQLite 方言）。

# 4. 数据模型设计

## 4.1 数据库选型（MVP）

可选方案：

| 方案                       | 优点 | 缺点 / 风险 | 适用阶段 |
|--------------------------|------|-------------|----------|
| **SQLite/H2（MVP prefer）**  | 零运维、启动快、配置简单 | 并发写入能力弱于生产级数据库；不适合高可用与大规模场景 | MVP / 单机演示 |
| **MySQL（生产推荐）**          | 团队普及高；事务与索引能力成熟；与 Spring Data JPA 生态匹配；运维经验丰富 | 扫描型调度在高并发场景下可能有热点与锁竞争，需要索引和分片优化 | 中等规模到生产 |
| **PostgreSQL**           | SQL 能力强（CTE/JSONB/高级索引）；并发控制能力优秀；扩展性好 | 团队若经验不足，调优与运维门槛更高 | 中大规模，团队有 PG 经验 |
| **NoSQL（如 Redis/Mongo）** | 高吞吐、低延迟（特定场景） | 事务语义、复杂查询与状态审计成本高，不适配当前状态机调度主路径 | 特殊场景，不作为本方案首选 |

MVP 取舍：

- **默认选型**：SQLite（MVP demo 环境）；MySQL/PostgreSQL 作为生产迁移目标。
- **理由**：当前阶段优先保证演示效率和快速迭代，SQLite 可以零运维跑通“接入-调度-重试-死信”全流程。
- **代价**：并发写入、HA 与规模扩展能力有限；进入联调/生产前需要迁移到 MySQL 或 PostgreSQL。

## 4.2 表：`delivery_task`

字段定义：

- `task_id`：PK，varchar
- `biz_key`：varchar，not null
- `target_url`：varchar，not null
- `http_method`：varchar，not null，default `POST`
- `headers_json`：text，nullable
- `body`：clob/text，nullable
- `status`：varchar，not null
- `retry_count`：int，not null，default 0
- `max_retries`：int，not null，default 3
- `next_retry_at`：timestamp，nullable
- `error_message`：varchar(1024)，nullable
- `created_at`：timestamp，not null
- `updated_at`：timestamp，not null

建议索引：

- `idx_task_status_created_at(status, created_at)`
- `idx_task_status_next_retry_at(status, next_retry_at)`
- `idx_task_biz_key(biz_key)`

## 4.3 状态定义

- `PENDING`
- `DELIVERING`
- `RETRY_WAIT`
- `SUCCESS`
- `DEAD_LETTER`

# 5. API 设计

## 5.1 提交通知

- `POST /api/notifications`
- Request JSON:
  - `bizKey` (required)
  - `targetUrl` (required)
  - `httpMethod` (optional, default POST)
  - `headers` (optional, map<string,string>)
  - `body` (optional, string)
- Response:
  - `202`: `{ "taskId": "...", "message": "请求已接收" }`
  - `400`: 缺少必填字段
  - `500`: 持久化异常

## 5.2 查询任务

- `GET /api/notifications/{taskId}`
- `200`: 返回任务详情
- `404`: 任务不存在

## 5.3 按业务键查询

- `GET /api/notifications?bizKey=xxx`
- `200`: 返回任务列表（按 `createdAt` 升序）

## 5.4 查询死信

- `GET /api/notifications/dead-letters`
- `200`: 返回死信任务列表

## 5.5 死信重投

- `POST /api/notifications/dead-letters/{taskId}/retry`
- 行为：
  - 仅允许 `DEAD_LETTER` 状态重投；
  - 重置 `retryCount=0`、`errorMessage=null`、`nextRetryAt=null`、`status=PENDING`。

# 6. 调度流程（方案A落地）

调度周期由 `scheduler.poll-interval-ms` 配置（默认 5000ms）。

## 6.1 单次调度循环

每次调度：

1. 查询 `PENDING` 任务并逐个执行；
2. 查询 `RETRY_WAIT` 且 `nextRetryAt <= now` 任务并逐个执行；
3. 对每个任务执行：
   - CAS/条件更新为 `DELIVERING`（单实例可简化为直接更新）；
   - 调用执行器发送 HTTP；
   - 成功（2xx）置 `SUCCESS`；
   - 失败时 `retryCount + 1`：
     - 若 `retryCount >= maxRetries`：置 `DEAD_LETTER`；
     - 否则置 `RETRY_WAIT` 并写入 `nextRetryAt`。

## 6.2 关键伪代码

```java
for (task in findPendingAndDueRetryTasks()) {
  if (!markDelivering(task.id)) continue;
  result = executor.execute(task);
  if (result.success) markSuccess(task.id);
  else markFailureWithRetryOrDeadLetter(task.id, result.error);
}
```

# 7. 状态迁移约束

允许迁移：

- `PENDING -> DELIVERING`
- `DELIVERING -> SUCCESS`
- `DELIVERING -> RETRY_WAIT`
- `RETRY_WAIT -> DELIVERING`
- `DELIVERING -> DEAD_LETTER`
- `DEAD_LETTER -> PENDING`（人工重投）

约束说明：

- `RETRY_WAIT` 不直接到 `DEAD_LETTER`，必须先进入 `DELIVERING` 并经历一次失败判定；
- 非法迁移在 Service 层拒绝并记录告警日志。

# 8. 调度扩展点：重试策略

## 8.1 接口定义

- `RetrySchedulePolicy`：
  - 输入：`retryCountAfterIncrement`、`maxRetries`、可选上下文（错误类型/租户配置）
  - 输出：`Duration`（或等价的绝对 `nextRetryAt`）

## 8.2 默认实现与切换

- MVP 默认：`FixedIntervalRetrySchedulePolicy`
  - `nextDelay = scheduler.retry-interval-ms`
- 可扩展实现：
  - `ExponentialBackoffRetrySchedulePolicy`
  - `ExponentialWithJitterRetrySchedulePolicy`
  - `ClassifierRetrySchedulePolicy`
- 通过 `scheduler.retry-policy` 或 Bean 注入切换实现，无需修改调度主流程。

# 9. 执行模块细节

- 使用 `RestTemplate`（后续可替换 `WebClient`）；
- 默认 connect/read timeout：10s；
- `headers` 未指定 `Content-Type` 时默认 `application/json`；
- `httpMethod` 非法值降级为 `POST`；
- 统一封装为 `DeliveryResult(success, httpStatusCode, errorMessage)`。

# 10. 并发与一致性

## 10.1 MVP（单实例）

- 单实例调度，避免并发抢占复杂性；
- 所有状态变更写 DB，重启可恢复；
- 与 HLD 对齐：保证 at-least-once，不保证 exactly-once。

## 10.2 后续多实例扩展

- 方案一：乐观锁版本号；
- 方案二：条件更新抢占（`update ... where status in (...)`）；
- 方案三：分布式锁或分片调度。

# 11. 配置项

- `spring.datasource.url`：MVP 默认 SQLite（例如 `jdbc:sqlite:notification.db`）
- `spring.datasource.driver-class-name`：`org.sqlite.JDBC`
- `scheduler.poll-interval-ms`：调度轮询间隔
- `scheduler.retry-interval-ms`：固定间隔策略的重试间隔
- `scheduler.max-retries`：最大重试次数
- `scheduler.retry-policy`：重试策略选择（默认 `fixed`）

# 12. 可观测性与日志

建议指标：

- `delivery_total`
- `delivery_success_total`
- `delivery_failure_total`
- `delivery_retry_total`
- `delivery_dead_letter_total`
- `delivery_latency_ms`（建议新增）

关键日志字段：

- `taskId`
- `bizKey`
- `status`
- `retryCount`
- `httpStatusCode`
- `errorMessage`

# 13. 向 CDC(binlog) + MQ 演进的落地点（非 MVP）

- 接入阶段统一写入单表 `delivery_task`（元信息 + 消息内容）；
- 通过 CDC 组件（如 Debezium/Canal）订阅 MySQL binlog，将新增任务异步发布到 MQ (消息内容，MQ只负责消息内容即可，任务控制还放在DB)；
- 发布消息携带 `bizKey` 用于下游幂等，同时可以根据 `bizKey` 发布到不同分区，实现业务隔离和提高吞吐量；
- Consumer基于 `bizKey` 做幂等消费，执行投递后更新 `delivery_task` 状态；
- 保持状态机和 `RetrySchedulePolicy` 抽象不变，仅替换“任务驱动入口”。

## 13.1 分区与有序性建议

- 发送到 MQ 时使用 `bizKey` 作为 partition key（如 Kafka message key），让同一 `bizKey` 的消息稳定路由到同一分区；
- 单分区内由单消费线程顺序消费时，可尽最大可能保证同 `bizKey` 的投递顺序；
- 该方案不是绝对有序：重试、死信、人工重投会打破全局顺序；语义上仍是 at-least-once + best-effort per-key ordering；
- 需要在消费侧继续做幂等（基于 `bizKey`）以容忍 CDC 重放、重复发布和重复消费。

## 13.2 分区并行与吞吐

- 多分区可并行消费，从而并行投递不同 `bizKey` 的消息，显著提升吞吐；
- 分区数建议与消费并发/下游容量一起评估，避免“分区过多导致运维复杂”或“分区过少导致热点瓶颈”；
- 对热点 `bizKey`（超高频单 key）仍可能形成单分区瓶颈，需要配合限流或业务侧拆 key 策略。

## 13.3 CDC 一致性与过滤规则

- CDC 只监听“任务创建事件”或白名单字段变化，避免把状态更新事件再次投递到 MQ 形成回环；
- 明确位点管理与重放策略（断点恢复后允许重复发布，靠消费幂等兜底）；
- 当 binlog 延迟升高时，需提供监控与告警，保证“接收成功到进入 MQ”的时延可观测。
