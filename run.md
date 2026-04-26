# 通知投递平台 - 运行指南

## 项目结构

```
src/main/java/org/example/notification/
├── NotificationApplication.java          # Spring Boot启动类
├── config/
│   └── SQLiteDialect.java                # SQLite Hibernate方言
├── controller/
│   └── NotificationController.java        # 接入与查询API
├── service/
│   ├── NotificationService.java           # 任务接收、调度、重试、死信
│   └── DeliveryService.java               # 对外HTTP投递执行
├── repository/
│   └── TaskRepository.java                # 持久化访问层
├── model/
│   ├── NotificationRequest.java          # 通知请求模型
│   ├── DeliveryTask.java                 # 投递任务实体(JPA)
│   ├── TaskStatus.java                   # 任务状态枚举(PENDING/DELIVERING/RETRY_WAIT/SUCCESS/DEAD_LETTER)
│   └── DeliveryResult.java               # 投递结果
```
## 分层与模块映射

| 分层 | 实现 | 职责 |
|------|------|------|
| Controller | NotificationController | 对外API入口、参数校验、返回结果 |
| Service | NotificationService | 业务编排、DB状态机调度、重试/死信处理 |
| Service | DeliveryService | 外部HTTP调用执行，统一结果封装 |
| Repository | TaskRepository | DeliveryTask 持久化查询与扫描 |

## 关键设计实现

- 至少一次投递：DB状态机 + 重试机制保证
- 3次重试 + 死信管理：retryCount >= maxRetries -> DEAD_LETTER
- bizKey透传：支持外部系统幂等
- 请求内容持久化：headers/body与任务状态统一存入SQLite


## 启动服务

```bash
# 打包
mvn package -DskipTests

# 启动（服务监听8080端口）
java -jar target/rc_xingzhong-1.0-SNAPSHOT.jar
```

SQLite 数据文件默认生成在项目根目录：`notification.db`

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

### 2. 按 taskId 查询任务（预期返回任务详情）

```bash
curl http://localhost:8080/api/notifications/{taskId}
```

### 3. 按 bizKey 查询任务列表

```bash
curl "http://localhost:8080/api/notifications?bizKey=order-12345"
```

### 4. 参数校验 - 缺少bizKey（预期返回400）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"targetUrl": "https://httpbin.org/post"}'
```

### 5. 参数校验 - 缺少targetUrl（预期返回400）

```bash
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"bizKey": "test-001"}'
```

### 6. 测试重试+死信（目标不可达，3次重试后进入死信）

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

提交后观察控制台日志，会看到重试后进入死信。

### 7. 查询死信 + 人工重投

```bash
# 查询死信任务
curl http://localhost:8080/api/notifications/dead-letters

# 对某个死信任务触发重投
curl -X POST http://localhost:8080/api/notifications/dead-letters/{taskId}/retry
```
