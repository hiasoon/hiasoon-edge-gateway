# HEG 1.0.1 Release Notes

发布日期：2026-11-09

本版本为 **安全修复 + 数据通道完善** 版本。核心变更是云端 `wss` 连接不再无条件信任服务端证书，恢复正常 TLS 校验链路；同时补齐边缘执行器与云端 Redis / MongoDB 的命令覆盖缺口。

### 产物

| 文件 | 说明 | 大小 |
|---|---|---|
| hiasoon-edge-gateway-1.0.1.tar | Docker 镜像离线包（tag `hiasoon/hiasoon-edge-gateway:1.0.1`） | ~108 MB |

### 快速部署

```bash
docker load -i hiasoon-edge-gateway-1.0.1.tar

docker run -d --name heg --restart unless-stopped \
  -p 9000:9000 \
  --env-file .env \
  -v /opt/heg/config:/app/data \
  -v /opt/heg/logs:/app/logs \
  hiasoon/hiasoon-edge-gateway:1.0.1
```

### 本版变更

#### 1. 修复 wss 证书不校验（安全）

1.0.0 的连接器使用 `InsecureTrustManagerFactory`，实际上**信任任意服务端证书**，链路可被中间人伪造。

1.0.1 改为：

- 默认使用 JDK 内置信任库校验证书链，并用 `endpointIdentificationAlgorithm=HTTPS` 校验主机名；
- Let's Encrypt 等公共 CA 签发的证书**零配置可用**；
- 私有 CA / 自签证书通过 `HEG_TLS_TRUST_STORE` 指定 PEM / JKS / PKCS12 后正常工作；
- 仅保留显式的临时降级开关 `HEG_TLS_INSECURE_SKIP_VERIFY=true`，开启时打印严重安全告警。

**升级影响（重要）**：使用自签证书的存量客户升级后，若未提供信任库，会因 `PKIX path building failed` 无法连接云端。请二选一：

```bash
# 方案 A（推荐）：提供 CA 证书
-e HEG_TLS_TRUST_STORE=/app/config/ca.pem

# 方案 B（仅临时联调）：显式降级
-e HEG_TLS_INSECURE_SKIP_VERIFY=true
```

#### 2. Redis 命令覆盖补齐

边缘 `RedisExecutor` 支持的 Redis 命令由 87 条补齐至 **107 条**，与云端 `RedisTemplate` 重写集合双向对齐，补入 `HDEL` 多字段、`LPOP/RPOP` 带 count、`LPOS`、`LMOVE`/`BLMOVE`、`SMISMEMBER`、`SMOVE`、`HRANDFIELD`、`HSTRLEN`、集合多键变体等；`bitField` 由错误的 `toString()` 序列化改为按 Redis 语法拼装。

#### 3. MongoDB 补齐建集合与 upsert

- 边缘 `MongoExecutor` 新增 `CREATE_COLLECTION` 指令与 upsert 支持（此前云端 usercenter 已有真实调用，边缘侧缺失会导致指令落空）；
- 修正 `DataSourceFactory.getCurrentMongoTemplate()` 边缘分支漏调 `registerMongoTemplate`，导致多数据源切换后取不到模板的问题；
- `HegMongoTemplate` 补 `updateFirst(Class)`、`updateMulti`、`upsert`、`findAndModify`。

### 兼容性

- 协议、配置键、HTTP 端点、镜像入口均与 1.0.0 兼容，配置文件可直接沿用；
- 唯一行为变化是 TLS 校验（见上）；
- 运行环境仍为 JDK 17，基础镜像 `eclipse-temurin:17-jre-alpine`。

### 依赖声明

本项目依赖 `com.hiasoon:hiasoon-api-sdk:1.0.0`（该 SDK 版本本版未变动），已发布至 Maven Central，构建时自动下载：

https://repo1.maven.org/maven2/com/hiasoon/hiasoon-api-sdk/1.0.0/

SDK 仓库：https://github.com/hiasoon/hiasoon-api-sdk

### 历史版本

| 版本 | 日期 | 说明 |
|---|---|---|
| 1.0.1 | 2026-11-09 | 修复 wss 证书不校验；Redis 107 条命令；Mongo 建集合与 upsert |
| 1.0.0 | 2026-06-30 | 首个开源版本，支持 MySQL / Redis / MongoDB 操作转发与 HTTP 转发 |

### 文档

- README.md：项目总览、配置清单、快速开始
- DEPLOY.md：Docker 离线部署、服务器落地指南
- HEG_TECH_DOC.md：协议定义、接口契约、内部设计

### License

Apache License 2.0
