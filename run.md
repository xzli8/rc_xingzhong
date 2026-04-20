# 通知投递平台 - 运行指南

## 项目结构

```
src/main/java/org/example/notification/
├── NotificationApplication.java          # Spring Boot启动类
├── model/
│   ├── NotificationRequest.java          # 通知请求模型
│   ├── DeliveryTask.java                 # 投递任务实体(JPA)
│   ├── TaskStatus.java                   # 任务状态枚举(PENDING/DELIVERING/SUCCESS/RETRYING/DEAD_LETTER)
│   └── DeliveryResult.java              # 投递结果
├── intake/
│   └── IntakeController.java             # 接入模块 - POST /api/notifications
├── scheduler/
│   └── DispatchScheduler.java            # 调度模块 - 任务管理、状态机、重试/死信
├── persistence/
│   ├── TaskRepository.java               # DB持久化(H2内存数据库)
│   ├── MessageQueue.java                 # 内存MQ(ConcurrentLinkedQueue模拟)
│   └── MessagePayload.java              # MQ消息载体
└── executor/
    └── DeliveryExecutor.java             # 执行模块 - RestTemplate调用外部HTTP
```
## 4个核心模块对应设计文档

| 模块 | 实现 | 职责 |
|------|------|------|
| 接入模块 | IntakeController | POST /api/notifications，校验必填字段，返回202 |
| 投递调度模块 | DispatchScheduler | 任务封装、状态流转、重试/死信逻辑 |
| 持久化模块 | TaskRepository + MessageQueue | DB存元信息 + MQ存消息内容 |
| 投递执行模块 | DeliveryExecutor | RestTemplate调用外部HTTP，2xx=成功 |

## 关键设计实现

- 至少一次投递：MQ + 重试机制保证
- 3次重试 + 死信管理：retryCount >= maxRetries → DEAD_LETTER
- bizKey透传：支持外部系统幂等
- 双持久化：H2(DB元信息) + ConcurrentLinkedQueue(模拟MQ)



## 启动服务

```bash
# 打包
mvn package -DskipTests

# 启动（服务监听8080端口）
java -jar target/rc_xingzhong-1.0-SNAPSHOT.jar
```

## 基本测试

### 1. 正常提交通知（预期返回202）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "bizKey": "order-12345",
    "targetUrl": "https://httpbin.org/post",
    "httpMethod": "POST",
    "headers": {"X-Source": "test"},
    "body": "{\"event\":\"payment_success\"}"
  }'
```

预期响应：
```json
{"taskId":"uuid","message":"请求已接收"}
```

### 2. 参数校验 - 缺少bizKey（预期返回400）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"targetUrl": "https://httpbin.org/post"}'
```

### 3. 参数校验 - 缺少targetUrl（预期返回400）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"bizKey": "test-001"}'
```

### 4. 测试重试+死信（目标不可达，3次重试后进入死信）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "bizKey": "retry-test",
    "targetUrl": "http://localhost:9999/not-exist",
    "httpMethod": "POST",
    "body": "{}"
  }'
```

提交后观察控制台日志，会看到重试3次后进入死信的过程。
