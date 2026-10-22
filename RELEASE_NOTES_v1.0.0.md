## 首次开源 Release

HEG（Hiasoon Edge Gateway）边缘数据访问网关首个开源版本。通过 WebSocket 接收云端下发指令，在本地执行 MySQL、Redis、MongoDB 操作或 HTTP 转发，适用于内网数据不出域、云端统一编排的场景。

### 产物

| 文件 | 说明 | 大小 |
|---|---|---|
| hiasoon-edge-gateway-1.0.0.tar | Docker 镜像离线包（tag `hiasoon/hiasoon-edge-gateway:1.0.0`） | ~108 MB |

### 快速部署

离线环境导入镜像并运行：

```bash
docker load -i hiasoon-edge-gateway-1.0.0.tar

docker run -d --name heg --restart unless-stopped \
  -p 9000:9000 \
  --env-file .env \
  -v /opt/heg/config:/app/data \
  -v /opt/heg/logs:/app/logs \
  hiasoon/hiasoon-edge-gateway:1.0.0
```

### 核心特性

- WebSocket 长连接自动重连、心跳保活
- 多数据源按 `appId` 路由（MySQL / Redis / MongoDB）
- 连接池管理（Druid / Jedis）
- HTTP 转发（支持自定义透传请求头）
- 配置热更新 + 文件持久化
- 内置监控页面 `/monitor`
- 容器化交付

### 依赖声明

本项目依赖 `com.hiasoon:hiasoon-api-sdk:1.0.0`，该组件已发布至 Maven Central，构建时自动下载，无需额外配置：

https://repo1.maven.org/maven2/com/hiasoon/hiasoon-api-sdk/1.0.0/

SDK 仓库：https://github.com/hiasoon/hiasoon-api-sdk

### 文档

- README：项目总览、配置清单、快速开始
- DEPLOY.md：Docker 离线部署、服务器落地指南
- HEG_TECH_DOC.md：协议定义、接口契约、内部设计

### License

Apache License 2.0
