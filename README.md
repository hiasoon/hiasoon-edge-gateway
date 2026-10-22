# HEG · Hiasoon Edge Gateway

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Docker](https://img.shields.io/badge/Docker-ready-blue.svg)](https://www.docker.com/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

面向边缘节点的数据访问网关。HEG 通过 WebSocket 与云端保持长连接，接收下发的数据操作指令，在本地执行 MySQL、Redis、MongoDB 操作或 HTTP 转发，并将结果回传云端。适用于内网数据不出域、云端统一编排的场景。

> English: HEG is an edge-side data access gateway that executes MySQL / Redis / MongoDB operations and HTTP forwarding on the local network, driven by cloud-issued commands over WebSocket.

网关需配合云端服务下发指令才能完成业务链路；未接入云端时，仅本地测试接口与监控页面可用。

---

## 目录

- [特性](#特性)
- [架构](#架构)
- [快速开始](#快速开始)
- [配置](#配置)
- [接口说明](#接口说明)
- [项目结构](#项目结构)
- [安全说明](#安全说明)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 特性

- **WebSocket 长连接**：自动重连、心跳保活，与云端网关保持稳定通信。
- **多数据源执行**：在同一网关内管理多组 MySQL、Redis、MongoDB 数据源，按 `appId` 路由。
- **连接池管理**：MySQL 使用 Druid 连接池，Redis 基于 Jedis，支持运行期按需创建与回收。
- **HTTP 转发**：将云端请求转发至本地内网服务，支持自定义透传请求头。
- **配置热更新**：云端可下发数据源配置变更，本地持久化后即时生效。
- **可观测性**：内置监控页面，支持在线查看、测试数据源与查询日志。
- **容器化交付**：提供 Dockerfile 与构建脚本，可离线导出镜像 tar 包部署到无外网环境。

## 架构

```
┌─────────────────┐        WebSocket (wss)        ┌──────────────────────────┐
│  云端控制台/服务  │ ◀───────────────────────────▶ │  HEG 边缘网关             │
└─────────────────┘                                │                          │
                                                   │  ┌────────────────────┐  │
                                                   │  │ OperationDispatcher│  │
                                                   │  └─────────┬──────────┘  │
                                                   │            │             │
                                                   │  ┌─────────┼───────────┐ │
                                                   │  ▼         ▼           ▼ │
                                                   │ MySQL     Redis     MongoDB│
                                                   │  │         │           │ │
                                                   │  └─────────┴───────────┘ │
                                                   │        HTTP 转发          │
                                                   └──────────────────────────┘
```

一次完整链路：云端下发 `operation` 指令 → `HegWebSocketClientHandler` 接收并校验 → `OperationDispatcher` 按类型分发 → 对应执行器操作本地资源 → 结果经 WebSocket 回传。

## 快速开始

### 环境要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | 编译与运行 |
| Maven | 3.6+ | 构建 |
| Docker | 20.10+ | 可选，构建/运行镜像 |

> **说明**：项目依赖 `com.hiasoon:hiasoon-api-sdk`（当前版本 `1.0.0`），已发布至 [Maven Central](https://repo1.maven.org/maven2/com/hiasoon/hiasoon-api-sdk/1.0.0/)，构建时会自动下载，无需额外配置。

### 本地构建与运行

```bash
git clone <仓库地址>
cd hiasoon-edge-gateway

# 打包
./heg.sh package

# 通过环境变量提供云端接入信息（不要写入仓库）
export HEG_WEBSOCKET_URL="wss://api.hiasoon.com/ws"
export HEG_APP_ID="<您的APPID>"
export HEG_SECRET_KEY="<您的SecretKey>"

java -jar target/hiasoon-edge-gateway-1.0.0.jar
```

Windows 环境在 PowerShell 中使用 `$env:HEG_APP_ID="..."` 设置变量；也可以直接编辑 `src/main/resources/application.yml`（请勿提交真实凭据）。

### 使用 Docker

方式一：构建并导出镜像包

```bash
./heg.sh build            # 打包 + 构建镜像 + 导出 tar
docker load -i hiasoon-edge-gateway-1.0.0.tar
docker run -d --name heg --restart unless-stopped -p 9000:9000 \
  --env-file .env \
  -v /opt/heg/config:/app/config \
  -v /opt/heg/logs:/app/logs \
  hiasoon-edge-gateway:1.0.0
```

方式二：Docker Compose

```bash
cp .env.example .env      # 填写 HEG_APP_ID / HEG_SECRET_KEY 等
./heg.sh package          # 镜像构建依赖 target/*.jar
docker compose up -d --build
```

镜像名与版本默认取自 `pom.xml`，可通过 `IMAGE_NAME`、`IMAGE_TAG` 覆盖。构建与部署细节见 [DEPLOY.md](DEPLOY.md)。

## 配置

默认读取 `application.yml`，其中所有可变项均可通过环境变量覆盖（格式 `${变量名:默认值}`）。所有敏感字段都应以环境变量注入，不要写入仓库。

### 核心环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HEG_SERVER_PORT` | `9000` | HTTP 服务端口 |
| `HEG_WEBSOCKET_URL` | `wss://api.hiasoon.com/ws` | 云端 WebSocket 地址 |
| `HEG_APP_ID` | 空 | 应用 ID，用于数据源路由（必填） |
| `HEG_SECRET_KEY` | 空 | 应用密钥，用于 HMAC-SHA256 Token 签名（必填） |
| `HEG_API_BASE_URL` | `https://api.hiasoon.com` | 云端 API 地址 |
| `HEG_MYSQL_APP_ID` | 同 `HEG_APP_ID` | MySQL 数据源绑定的应用 ID |
| `HEG_MYSQL_URL` | 空 | JDBC 连接串，留空则不启用该数据源 |
| `HEG_MYSQL_USERNAME` / `HEG_MYSQL_PASSWORD` | `root` / 空 | 数据库账号 |
| `HEG_MYSQL_INITIAL_SIZE` / `HEG_MYSQL_MAX_ACTIVE` | `5` / `20` | 连接池初始与最大连接数 |
| `HEG_REDIS_APP_ID` | 同 `HEG_APP_ID` | Redis 数据源绑定的应用 ID |
| `HEG_REDIS_HOST` / `HEG_REDIS_PORT` | 空 / `6379` | Redis 地址，host 留空则不启用 |
| `HEG_REDIS_PASSWORD` / `HEG_REDIS_DATABASE` | 空 / `0` | Redis 认证与库号 |
| `HEG_MONGO_APP_ID` | 同 `HEG_APP_ID` | MongoDB 数据源绑定的应用 ID |
| `HEG_MONGO_URI` / `HEG_MONGO_DATABASE` | 空 | MongoDB 连接串与库名，URI 留空则不启用 |
| `HEG_LOG_PATH` / `HEG_LOG_LEVEL` | `/app/logs` / `INFO` | 运行日志目录与级别 |
| `HEG_LOG_RETAIN_DAYS` | `30` | 日志保留天数 |
| `HEG_LOG_PERSISTENCE_ENABLED` | `false` | 是否持久化 SQL / 转发明细日志 |
| `HEG_SQL_LOG_PATH` / `HEG_PROXY_LOG_PATH` | `/app/logs/sql/` / `/app/logs/proxy/` | 明细日志目录 |
| `HEG_LOG_MAX_FILE_SIZE_MB` | `100` | 明细日志单文件大小上限（MB） |
| `HEG_CONFIG_PERSISTENCE_TYPE` | `file` | 运行期配置存储方式：`memory` / `file` |
| `HEG_CONFIG_FILE_PATH` | `/app/data/heg-runtime-config.yml` | 运行期配置落盘路径 |
| `HEG_PROXY_HEADERS` | `X-Custom-Header,X-Request-Id,X-User-Id` | HTTP 转发允许透传的请求头 |

完整变量列表见 [.env.example](.env.example)，配置结构见 [application.yml](src/main/resources/application.yml)。

### 配置热更新

云端可下发数据源与转发规则变更，网关更新内存配置后按 `HEG_CONFIG_PERSISTENCE_TYPE` 处理：

- `memory`：仅内存生效，重启后丢失；
- `file`：写入 `HEG_CONFIG_FILE_PATH`，重启后自动恢复。**注意**：落盘文件中已保存的值会覆盖环境变量提供的值，排查配置不生效时请先检查该文件。

默认开启 `file` 持久化。容器部署时请将该路径挂载到持久卷，或显式设置为 `memory`。

## 接口说明

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/heg/test/health` | 健康检查，返回服务状态 |
| `POST` | `/heg/test/mysql` | 执行 MySQL 操作 |
| `POST` | `/heg/test/redis` | 执行 Redis 操作 |
| `POST` | `/heg/test/mongodb` | 执行 MongoDB 操作 |
| `POST` | `/heg/test/http-forward` | 执行 HTTP 转发 |
| `GET` / `POST` | `/proxy/**` | 业务代理入口，转发至配置的目标地址 |
| `GET` | `/monitor` | 监控页面：数据源状态、SQL 与转发日志、在线配置 |
| `GET` | `/monitor/api/connection-status`、`/api/stats` | 连接状态与统计数据（JSON） |
| `POST` | `/monitor/api/config/*` | 在线修改云端、数据源、转发头等配置 |

请求/响应体定义见 [HEG_TECH_DOC.md](src/main/resources/HEG_TECH_DOC.md)。

## 项目结构

```
hiasoon-edge-gateway/
├── src/main/java/com/hiasoon/heg/
│   ├── config/          # 配置模型与数据源连接池
│   ├── controller/      # 测试与监控接口
│   ├── entity/          # 请求/响应实体
│   ├── executor/        # MySQL / Redis / MongoDB / HTTP 执行器
│   ├── websocket/       # WebSocket 客户端、处理器与 Token 生成
│   └── HegApplication.java
├── src/main/resources/
│   ├── application.yml  # 应用配置
│   ├── templates/       # 监控页面模板
│   └── HEG_TECH_DOC.md  # 技术设计文档
├── Dockerfile
├── docker-compose.yml
├── heg.sh               # 构建与打包脚本
└── pom.xml
```

## 安全说明

- **凭据管理**：`HEG_APP_ID`、`HEG_SECRET_KEY`、数据库口令等一律通过环境变量注入，不要写入 `application.yml`、代码或脚本并提交。
- **接口鉴权**：`/monitor` 与 `/heg/test/*` 默认不提供内置认证，仅应在内网或受控网络暴露；如需公网访问，请置于反向代理后并增加认证与访问控制。
- **日志内容**：开启日志持久化可能记录 SQL 语句与参数，请评估合规要求后再启用。
- **传输安全**：生产环境请使用 `wss://`，避免明文传输密钥与业务数据。
- **漏洞反馈**：请勿通过公开 Issue 提交安全问题，直接联系维护者。

## 参与贡献

欢迎提交 Issue 与 Pull Request。

1. Fork 仓库并基于最新主分支创建特性分支：`feature/your-feature` 或 `fix/your-fix`。
2. 保持改动聚焦，提交前执行：
   ```bash
   bash -n heg.sh
   mvn clean package -DskipTests
   ```
3. 提交信息遵循 `type(scope): subject` 约定，例如 `fix(redis): handle reconnect timeout`。
4. 提交 PR 时说明变更动机、影响范围与验证方式。

请勿在 PR 中包含任何真实凭据、生产地址或内部环境信息。

## 许可证

本项目采用 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 许可。
