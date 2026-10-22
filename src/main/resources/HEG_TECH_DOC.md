# HEG (Hiasoon Edge Gateway) 技术文档

## 1. 项目概述

### 1.1 项目简介

**HEG (Hiasoon Edge Gateway)** 是氦闪边缘网关的精简验证版，用于验证Business服务与边缘设备之间的数据交互能力。

| 属性 | 值 |
|------|-----|
| 项目名称 | heg-verify |
| 版本 | 1.0.0 |
| 语言 | Java 1.8 |
| 框架 | Spring Boot 2.7.18 |
| 端口 | 9000 |

### 1.2 核心功能

- **MySQL操作转发**: 支持SELECT/INSERT/UPDATE/DELETE/CREATE/ALTER等SQL操作
- **Redis操作转发**: 支持GET/SET/DEL/HGET/HSET等Redis命令
- **MongoDB操作转发**: 支持FIND/INSERT/UPDATE/DELETE等MongoDB操作
- **HTTP请求转发**: 将客户端请求转发到氦闪中台
- **WebSocket通信**: 与Business服务建立长连接，接收操作请求并返回结果

### 1.3 架构定位

```
┌─────────────────────────────────────────────────────────────────────┐
│                        氦闪中台 (Cloud)                              │
├─────────────────────────────────────────────────────────────────────┤
│                          Business服务                                │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  HegMybatisInterceptor ──→ HegClient ──→ WebSocket Feign    │    │
│  │  SqlExecutionService ───────┘                               │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
                                │ WebSocket
                                ↓
┌─────────────────────────────────────────────────────────────────────┐
│                          HEG (边缘网关)                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │ MysqlExecutor│  │RedisExecutor │  │MongoExecutor │             │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘             │
│         │                 │                 │                      │
│         ↓                 ↓                 ↓                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│  │   MySQL      │  │    Redis     │  │   MongoDB    │             │
│  └──────────────┘  └──────────────┘  └──────────────┘             │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 2. 技术架构

### 2.1 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.7.18 | 应用框架 |
| Netty | 4.1.100.Final | WebSocket通信 |
| OkHttp | 4.12.0 | HTTP请求转发 |
| FastJSON | 1.2.83 | JSON序列化/反序列化 |
| Druid | 1.2.20 | MySQL连接池 |
| MySQL Connector | 8.0.26 | MySQL驱动 |
| Jedis | 3.x | Redis客户端 |
| MongoDB Driver | 4.x | MongoDB客户端 |

### 2.2 项目结构

```
hiasoon-edge-gateway/
├── README.md                            # 项目说明
├── src/main/java/com/hiasoon/heg/
│   ├── HegApplication.java              # 启动类
│   ├── config/
│   │   ├── HegConfig.java               # 配置类(Cloud/Mysql/Redis/MongoDB)
│   │   └── DataSourcePool.java          # 数据源连接池管理
│   ├── controller/
│   │   └── HegTestController.java       # 测试接口
│   ├── entity/
│   │   ├── HegOperationRequest.java     # 操作请求实体
│   │   ├── HegOperationResponse.java    # 操作响应实体
│   │   ├── RedisOperation.java          # Redis操作实体
│   │   └── MongoOperation.java          # MongoDB操作实体
│   ├── executor/
│   │   ├── OperationDispatcher.java     # 操作分发器
│   │   ├── MysqlExecutor.java           # MySQL执行器
│   │   ├── RedisExecutor.java           # Redis执行器
│   │   ├── MongoExecutor.java           # MongoDB执行器
│   │   └── HttpForwardExecutor.java     # HTTP转发执行器
│   ├── util/
│   │   └── Sign.java                    # 参数签名工具
│   └── websocket/
│       ├── HegWebSocketClient.java      # WebSocket客户端
│       ├── HegWebSocketClientHandler.java # WebSocket处理器
│       └── TokenGenerator.java          # Token生成器
├── src/main/resources/
│   ├── application.yml                  # 应用配置
│   └── HEG_TECH_DOC.md                  # 技术文档(本文档)
└── pom.xml                              # Maven依赖
```

### 2.3 核心组件说明

#### 2.3.1 WebSocket通信模块

| 类名 | 职责 |
|------|------|
| `HegWebSocketClient` | WebSocket客户端，负责与Business服务建立连接 |
| `HegWebSocketClientHandler` | WebSocket消息处理器，处理消息收发 |
| `TokenGenerator` | 生成WebSocket握手所需的Token |

#### 2.3.2 配置管理模块

| 类名 | 职责 |
|------|------|
| `HegConfig` | 配置类，映射application.yml中的heg配置 |
| `DataSourcePool` | 数据源连接池管理，初始化/获取/关闭MySQL/Redis/MongoDB连接 |

#### 2.3.3 操作执行模块

| 类名 | 职责 |
|------|------|
| `OperationDispatcher` | 操作分发器，根据dbType分发到对应执行器 |
| `MysqlExecutor` | MySQL执行器，执行SQL语句，支持多条SQL拆分执行 |
| `RedisExecutor` | Redis执行器，执行Redis命令 |
| `MongoExecutor` | MongoDB执行器，执行MongoDB操作 |
| `HttpForwardExecutor` | HTTP转发执行器，转发请求到中台 |

---

## 3. 核心流程

### 3.1 WebSocket连接流程

```
1. HEG启动时自动创建WebSocketClient
2. 通过TokenGenerator生成握手Token
3. 连接到Business服务的WebSocket端点 (ws://localhost:9602/heg)
4. 握手成功后保持长连接
5. 接收Business发送的操作请求
6. 执行操作并返回结果
```

### 3.2 操作执行流程

```
Business服务发起请求
       │
       ↓
WebSocket消息发送 (HegOperationRequest)
       │
       ↓
HEG接收消息
       │
       ↓
OperationDispatcher.dispatch()
       │
       ├─── dbType=MYSQL → MysqlExecutor.execute()
       │       │
       │       ├── 拆分多条SQL (支持注释/字符串/括号)
       │       ├── 执行SQL
       │       └── 返回结果
       │
       ├─── dbType=REDIS → RedisExecutor.execute()
       │
       ├─── dbType=MONGODB → MongoExecutor.execute()
       │
       └─── dbType=HTTP → HttpForwardExecutor.execute()
```

### 3.3 MySQL执行器核心逻辑

```
execute(request)
       │
       ↓
splitSqlStatements(sql)      # 智能拆分多条SQL
       │
       ├── 支持字符串内分号（不拆分）
       ├── 支持注释（-- 和 /* */）
       └── 支持括号嵌套
       │
       ↓
如果多条SQL → executeMultipleStatements()
       │       ├── 开启事务
       │       ├── 逐条执行
       │       ├── 成功提交 / 失败回滚
       │       └── 返回总影响行数
       │
否则 → 单条SQL执行
       ├── SELECT → executeQuery() → 返回List<Map>
       └── INSERT/UPDATE/DELETE/CREATE → executeUpdate() → 返回影响行数
```

---

## 4. 配置说明

### 4.1 application.yml 配置项

```yaml
server:
  port: 9000                    # HEG服务端口

heg:
  cloud:
    websocket-url: ws://localhost:9602/heg   # 云端WebSocket地址
    app-id: <您的APPID>
    secret-key: <您的SecretKey>
    api-base-url: https://api.hiasoon.com   # 氦闪中台API地址

  mysql:                        # MySQL数据源列表
    - app-id: <您的APPID>       # 必须与 cloud.app-id 一致
      url: jdbc:mysql://localhost:3306/your_database?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
      username: <数据库用户名>
      password: <数据库密码>
      initial-size: 5
      max-active: 20

  redis:                        # Redis数据源列表
    - app-id: <您的APPID>       # 必须与 cloud.app-id 一致
      host: localhost
      port: 6379
      password: <密码，无密码留空>
      database: 0

  mongodb:                      # MongoDB数据源列表
    - app-id: <您的APPID>       # 必须与 cloud.app-id 一致
      uri: mongodb://localhost:27017/your_database
      database: your_database

logging:
  level:
    com.hiasoon.heg: DEBUG      # 日志级别
```

### 4.2 数据源配置说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `heg.mysql[].app-id` | 应用ID，用于多租户隔离 | - |
| `heg.mysql[].url` | MySQL连接URL | - |
| `heg.mysql[].username` | MySQL用户名 | - |
| `heg.mysql[].password` | MySQL密码 | - |
| `heg.mysql[].initial-size` | 连接池初始大小 | 5 |
| `heg.mysql[].max-active` | 连接池最大连接数 | 20 |

---

## 5. 数据模型

### 5.1 HegOperationRequest

```java
@Data
@Builder
public class HegOperationRequest {
    private String requestId;           // 请求ID(UUID)
    private String appId;               // 应用ID
    private String dbType;              // 数据源类型: MYSQL/REDIS/MONGODB/HTTP
    private String operationType;       // 操作类型: SELECT/INSERT/UPDATE/DELETE/CREATE等
    private String sql;                 // SQL语句(MySQL使用)
    private Map<String, Object> params; // 参数Map
    private List<Object> paramList;     // 参数列表
    private RedisOperation redisOp;     // Redis操作对象
    private MongoOperation mongoOp;     // MongoDB操作对象
    private Long timestamp;             // 时间戳
    private Boolean isCountQuery;       // 是否COUNT查询
}
```

### 5.2 HegOperationResponse

```java
@Data
@Builder
public class HegOperationResponse {
    private String requestId;           // 请求ID(与Request对应)
    private Integer code;               // 响应码: 200成功, 其他失败
    private String message;             // 响应消息
    private Object data;                // 响应数据
    private Long timestamp;             // 时间戳

    public static HegOperationResponse success(String requestId, Object data) { ... }
    public static HegOperationResponse error(String requestId, String code, String message) { ... }
}
```

---

## 6. API接口

### 6.1 测试接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/heg/test/mysql` | GET | 测试MySQL连接 |
| `/heg/test/redis` | GET | 测试Redis连接 |
| `/heg/test/mongodb` | GET | 测试MongoDB连接 |

---

## 7. 开发完善路线

### 7.1 方向一：验证Redis操作和Mongo操作

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| Redis命令覆盖 | P0 | 完善RedisExecutor，支持GET/SET/DEL/HGET/HSET/LRANGE/LPUSH/RPUSH/EXPIRE等常用命令 |
| Redis事务支持 | P1 | 支持Redis事务操作（MULTI/EXEC/DISCARD） |
| MongoDB操作覆盖 | P0 | 完善MongoExecutor，支持FIND/INSERT/UPDATE/DELETE/AGGREGATE/COUNT等操作 |
| MongoDB索引管理 | P1 | 支持MongoDB索引的创建、删除和查询 |
| 端到端测试 | P0 | 编写完整的Redis和MongoDB操作测试用例，验证从Business到HEG的完整链路 |

### 7.2 方向二：环境变量占位与Docker部署

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| 环境变量支持 | P0 | 在application.yml中引入环境变量占位符 `${HEG_MYSQL_URL}` 等 |
| Dockerfile编写 | P0 | 编写Dockerfile，支持多阶段构建，生成轻量级镜像 |
| Docker Compose | P0 | 提供docker-compose.yml，支持一键启动HEG + MySQL + Redis + MongoDB |
| 配置文件外部化 | P1 | 支持通过环境变量或外部配置文件覆盖默认配置 |
| 健康检查 | P0 | 添加Docker健康检查接口，支持容器编排平台的健康探测 |

### 7.3 方向三：配置热更新能力

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| 动态数据源管理 | P0 | 支持运行时添加/删除/修改MySQL/Redis/MongoDB数据源配置 |
| 配置监听 | P1 | 监听配置文件变化，自动刷新数据源 |
| 配置持久化 | P0 | 将动态配置持久化到本地文件或数据库，重启后自动恢复 |
| 配置校验 | P0 | 添加配置参数校验，防止非法配置导致服务异常 |
| 配置版本管理 | P2 | 支持配置版本记录和回滚 |

### 7.4 方向四：完善错误码体系

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| 错误码统一定义 | P0 | 定义统一的错误码枚举，涵盖MySQL/Redis/MongoDB/HTTP/WebSocket等模块 |
| 错误信息标准化 | P0 | 统一错误响应格式，包含错误码、错误消息、详细描述、建议解决方案 |
| 错误分类 | P1 | 按模块和严重程度分类错误，便于问题定位和统计 |
| 错误日志增强 | P0 | 在错误日志中包含完整的上下文信息（appId、SQL、参数等） |
| 客户端错误提示 | P1 | 为客户端提供友好的错误提示和处理建议 |

### 7.5 方向五：测试验证接口转发业务能力

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| HTTP转发测试 | P0 | 验证HTTP请求转发到氦闪中台的完整流程，包括URL转换、参数签名、请求头注入 |
| 业务场景测试 | P0 | 模拟真实业务场景，验证接口转发的正确性和性能 |
| 签名验证 | P0 | 确保签名机制与氦闪中台完全一致，防止签名错误导致请求被拒绝 |
| 超时处理 | P1 | 测试HTTP转发的超时机制，确保超时后能正确返回错误 |
| 并发测试 | P1 | 测试高并发场景下的HTTP转发性能和稳定性 |

### 7.6 方向六：完整的操作日志

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| 操作日志记录 | P0 | 记录所有MySQL/Redis/MongoDB/HTTP操作的详细日志 |
| 日志字段定义 | P0 | 定义日志字段：操作类型、操作时间、appId、SQL/命令、参数、结果、耗时、操作者等 |
| 日志持久化 | P0 | 将操作日志持久化到本地文件或数据库，支持历史查询 |
| 日志查询接口 | P1 | 提供操作日志查询接口，支持按时间、appId、操作类型等条件过滤 |
| 日志审计 | P2 | 支持操作日志的审计功能，满足合规要求 |

### 7.7 方向七：前端Web功能

| 子任务 | 优先级 | 说明 |
|--------|--------|------|
| 数据源可视化配置 | P0 | 提供Web界面，支持可视化配置MySQL/Redis/MongoDB数据源 |
| 状态监控可视化 | P0 | 展示HEG服务状态、WebSocket连接状态、数据源连接状态、连接池状态 |
| 操作日志可视化 | P0 | 提供操作日志的可视化展示，支持搜索、筛选、分页 |
| 实时监控 | P1 | 添加实时监控面板，展示实时请求量、响应时间、错误率等指标 |
| 告警通知 | P2 | 支持配置告警规则，当服务异常时发送告警通知 |

### 7.8 路线优先级汇总

| 优先级 | 方向 | 关键任务 |
|--------|------|----------|
| **P0** | 方向一 | 验证Redis和MongoDB操作功能 |
| **P0** | 方向二 | 环境变量占位 + Docker部署能力 |
| **P0** | 方向三 | 配置热更新能力（动态数据源管理） |
| **P0** | 方向四 | 完善错误码体系 |
| **P0** | 方向五 | 测试验证HTTP接口转发业务能力 |
| **P0** | 方向六 | 完整的操作日志记录和持久化 |
| **P0** | 方向七 | 前端Web功能（数据源配置、状态监控、日志可视化） |

### 7.9 技术债务清理

| 优先级 | 任务 | 说明 |
|--------|------|------|
| P1 | 代码重构 | 提取公共工具类，优化代码结构，提高可维护性 |
| P1 | 单元测试 | 为核心组件（执行器、分发器、WebSocket客户端）添加单元测试 |
| P1 | 性能优化 | 优化SQL执行、Redis操作、MongoDB操作的性能 |
| P2 | 安全加固 | 添加输入校验、SQL注入防护、参数过滤等安全措施 |

---

## 8. 运行说明

### 8.1 编译

```bash
mvn clean compile -DskipTests
```

### 8.2 运行

```bash
mvn spring-boot:run
```

### 8.3 打包

```bash
mvn clean package -DskipTests
java -jar target/hiasoon-edge-gateway-1.0.0.jar
```

---

## 9. 版本历史

| 版本 | 日期 | 变更说明 |
|------|------|----------|
| 1.0.0 | 2026-06-30 | 初始版本，支持MySQL/Redis/MongoDB操作转发和HTTP转发 |

---

**文档版本**: v1.0  
**最后更新**: 2026-06-30  
**作者**: Hiasoon Team
