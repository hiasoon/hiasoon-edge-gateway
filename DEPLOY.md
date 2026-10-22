# HEG 边缘网关 部署文档

> 适用版本：HEG 1.0.0
>
> 本文档分两部分：
> - **第一部分**：在构建机（CentOS 7）上如何从代码构建出 **Docker 镜像 tar 包**
> - **第二部分**：在客户服务器上如何把该 tar 包**加载成镜像**，并**启动容器**

---

## 〇、整体流程

```
┌──────────────────────────────────────────────────────────────┐
│ 构建机（我方可访问外网/CentOS 7）                              │
│   1. 获取源码      git clone 或直接使用已有代码                │
│   2. Maven 打包    mvn clean package → target/*.jar           │
│   3. 构建镜像      docker build -t hiasoon-edge-gateway:1.0.0 .           │
│   4. 导出镜像包    docker save → hiasoon-edge-gateway-1.0.0.tar                      │
└──────────────────────────────────────────────────────────────┘
                              │  上传 tar 包（未压缩）
                              ↓
┌──────────────────────────────────────────────────────────────┐
│ 客户服务器（Docker 环境，无需 Java / Maven / 源码）             │
│   5. 加载镜像      docker load -i hiasoon-edge-gateway-1.0.0.tar                     │
│   6. 准备配置      config/application.yml（改中台地址/APPID/库）│
│   7. 启动容器      docker run -d --name heg ...        │
│   8. 验证          docker logs -f heg / 健康检查        │
└──────────────────────────────────────────────────────────────┘
```

一键完成第 1~4 步：在构建机执行

```bash
./heg.sh build
```

---

# 第一部分：构建机操作（打镜像包）

## 1.1 环境要求（CentOS 7）

| 组件 | 版本要求   | 安装参考                                                     |
|------|--------|----------------------------------------------------------|
| git | 任意     | `yum install -y git`                                     |
| JDK | 17     | `yum install -y java-17-openjdk-devel`                   |
| Maven | 3.6+   | 下载 `apache-maven-3.8.x` 解压后配置 `PATH`                     |
| Docker | 20.10+ | `yum install -y docker && systemctl enable --now docker` |

> ⚠️ Maven 构建需要联网下载依赖。若构建机为内网环境，请提前配置好私服或本地仓库（`~/.m2/repository`）。
> ⚠️ 项目依赖 `com.hiasoon:hiasoon-api-sdk:1.0.0`，该组件已发布至 Maven Central，联网环境可直接自动下载；内网构建机请通过私服代理或提前放入本地仓库。

## 1.2 构建脚本说明（heg.sh）

脚本位于项目根目录（与 `src`、`pom.xml` 同级），已随代码提交：

| 命令 | 说明 |
|------|------|
| `./heg.sh package` | 仅执行 Maven 打包（生成 `target/*.jar`） |
| `./heg.sh image` | 仅构建镜像并导出 tar 包（需先执行 package） |
| `./heg.sh build` | **一步完成**：打包 + 构建镜像 + 导出 tar |
| `./heg.sh clean` | 清理 target/ 与镜像 tar 包 |
| `./heg.sh help` | 显示帮助 |

## 1.3 执行构建

```bash
# 进入项目根目录（与 pom.xml、heg.sh 同级）
cd hiasoon-edge-gateway
chmod +x heg.sh
./heg.sh build
```

## 1.4 构建产物

```
<项目根目录>/
└── hiasoon-edge-gateway-1.0.0.tar   # Docker 镜像包（未压缩），与 heg.sh 同级
```

镜像命名规则：`hiasoon-edge-gateway:<版本>`，版本自动取自 `pom.xml`，默认 `1.0.0`。
导出 tar 包命名为 `hiasoon-edge-gateway-<版本>.tar`，每次构建覆盖更新。
如需覆盖，可在执行时指定环境变量：`IMAGE_NAME=xxx IMAGE_TAG=1.2.3 ./heg.sh build`。

**查看镜像信息：**

```bash
docker images | grep hiasoon-edge-gateway
docker inspect hiasoon-edge-gateway:1.0.0 | grep -i version
```

---

# 第二部分：客户服务器操作（加载镜像 + 启动容器）

> 客户服务器**只需要 Docker**，不需要 Java、Maven、源码，也不需要会构建。

## 2.1 前置条件

| 项目 | 要求 |
|------|------|
| Docker | 20.10 及以上（`docker -v` 查看） |
| 出方向网络 | 能访问氦闪中台 WebSocket 端口（默认 `9602`）与网关端口（默认 `10000`） |
| 数据库 | 客户自己的 MySQL / Redis / MongoDB 已就绪，且服务器可访问 |

## 2.2 第一步：上传并加载镜像

将 `hiasoon-edge-gateway-1.0.0.tar` 上传到服务器（例如 `/opt/heg/`），然后：

```bash
cd /opt/heg

# 加载镜像（tar 包未压缩，无需 gunzip）
docker load -i hiasoon-edge-gateway-1.0.0.tar
```

**加载成功的输出示例：**

```
Loaded image: hiasoon-edge-gateway:1.0.0
```

**验证：**

```bash
docker images | grep hiasoon-edge-gateway
# hiasoon-edge-gateway   1.0.0   3f2a1b9c8d7e   2 minutes ago   289MB
```

> 说明：`docker load` 只是把镜像导入到本机镜像库，**此时还没有启动任何服务**。

## 2.3 第二步：准备配置文件（必做）

参考项目根目录的 `.env.example` 与 `src/main/resources/application.yml`，按实际环境填写后保存为 `/opt/heg/config/application.yml`：

```bash
mkdir -p /opt/heg/config /opt/heg/logs
vi /opt/heg/config/application.yml
```

> 说明：
> - 该文件**必须挂载**（见 2.4）才会生效；未挂载时程序使用镜像内置的默认配置；
> - 挂载后，文件中填写的值会**覆盖**镜像内置默认配置中的对应项，未填写的键继承默认值。

配置文件内容参考（**必须修改**的部分已标注）：

```yaml
server:
  port: 9000

heg:
  cloud:
    # 【必改】氦闪中台 HEG WebSocket 地址
    websocket-url: ws://<氦闪中台HEG服务地址>:9602/heg
    # 【必改】开发者平台分配的应用 APPID
    app-id: <您的APPID>
    # 【必改】开发者平台分配的应用密钥
    secret-key: <您的SecretKey>
    # 【必改】氦闪中台网关地址
    api-base-url: http://<氦闪中台网关地址>:10000

  # 【必改】本地数据源（app-id 必须与上方 app-id 一致）
  mysql:
    - app-id: <您的APPID>
      url: jdbc:mysql://host.docker.internal:3306/<数据库名>?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
      username: <数据库用户名>
      password: <数据库密码>
      initial-size: 5
      max-active: 20

  redis:
    - app-id: <您的APPID>
      host: host.docker.internal
      port: 6379
      password: <密码，无密码留空>
      database: 0

  mongodb:
    - app-id: <您的APPID>
      uri: mongodb://<用户名>:<密码>@host.docker.internal:27017/admin
      database: <数据库名>

  # 应用运行日志（logback 输出，按天滚动拆分）
  app-log:
    path: /app/logs
    level: INFO
    retain-days: 30

logging:
  config: classpath:logback-spring.xml
```

> ⚠️ **容器网络注意事项（最常见的坑）**
> 容器内的 `localhost` 指容器自身，不是宿主机。
> 数据库在宿主机上时请用 `host.docker.internal`（下方 `docker run` 已带 `--add-host`）；
> 数据库在其它服务器时直接填该服务器内网 IP。

## 2.4 第三步：启动容器

```bash
docker run -d \
  --name heg \
  --restart always \
  -p 9000:9000 \
  -v /opt/heg/config/application.yml:/app/config/application.yml \
  -v /opt/heg/logs:/app/logs \
  --add-host=host.docker.internal:host-gateway \
  hiasoon-edge-gateway:1.0.0
```

**参数说明：**

| 参数 | 说明 |
|------|------|
| `-d` | 后台运行 |
| `--name heg` | 容器名称（后续操作用这个名字） |
| `--restart always` | 开机 / 异常退出自动重启 |
| `-p 9000:9000` | 宿主机端口:容器端口（若 9000 被占用可改为 `9001:9000`） |
| `-v /opt/heg/config/application.yml:/app/config/application.yml` | 挂载配置文件（覆盖镜像内置默认值），改完 `docker restart heg` 即生效 |
| `-v /opt/heg/logs:/app/logs` | 挂载日志目录，便于在宿主机查看 |
| `--add-host=host.docker.internal:host-gateway` | 让容器内可通过 `host.docker.internal` 访问宿主机 |
| `hiasoon-edge-gateway:1.0.0` | 镜像名:标签（与 `docker load` 导入的一致） |

> **Windows 客户机**：PowerShell 中换行符为 `` ` ``，或把命令写成一行；
> 挂载路径改为 `-v D:\heg\config\application.yml:/app/config/application.yml`。

## 2.5 第四步：验证部署

**① 查看容器状态**

```bash
docker ps | grep heg
```

**② 查看启动日志（关键）**

```bash
docker logs -f heg
```

日志中出现以下内容表示部署成功：

```
========== HEG验证项目启动完成 ==========
MySQL连接池初始化成功: appId=您的APPID
Redis连接池初始化成功: appId=您的APPID
MongoDB客户端初始化成功: appId=您的APPID, database=xxx
HEG连接云端: ws://.../heg?appId=xxx&token=xxx
WebSocket握手完成
连接成功: ws://.../heg?appId=xxx&token=xxx
```

**③ 健康检查**

```bash
curl http://127.0.0.1:9000/heg/test/health
```

**④ 氦闪中台侧确认**

登录氦闪开发者平台，在对应应用下查看 HEG 连接状态应为**已连接**。

---

## 三、日常运维命令

```bash
# 查看实时日志
docker logs -f heg

# 查看最近 200 行
docker logs --tail 200 heg

# 修改配置后重启
docker restart heg

# 停止并删除容器（镜像保留）
docker stop heg && docker rm heg

# 查看容器挂载信息
docker inspect heg | grep -A 10 Mounts
```

---

## 四、升级与回滚

**升级：**

```bash
# 1. 加载新版本镜像（以 1.0.1 为例）
docker load -i hiasoon-edge-gateway-1.0.1.tar

# 2. 停止并删除旧容器（保留配置文件）
docker stop heg && docker rm heg

# 3. 用新镜像启动（配置沿用）
docker run -d --name heg --restart always \
  -p 9000:9000 \
  -v /opt/heg/config/application.yml:/app/config/application.yml \
  -v /opt/heg/logs:/app/logs \
  --add-host=host.docker.internal:host-gateway \
  hiasoon-edge-gateway:1.0.1
```

**回滚：** 直接按上面的方式用旧镜像 tag 重新启动容器即可（旧镜像不要删除）：

```bash
docker run -d --name heg ... hiasoon-edge-gateway:1.0.0
```

**清理旧镜像：**

```bash
docker rmi hiasoon-edge-gateway:1.0.0        # 确认无容器使用后再删除
```

---

## 五、常见问题（FAQ）

### Q1：日志报「WebSocket连接失败」

1. 确认 `websocket-url`、`app-id`、`secret-key` 与开发者平台一致；
2. 测试网络连通性：`telnet <中台地址> 9602`（或 `nc -zv <中台地址> 9602`）；
3. 确认服务器出方向防火墙/安全组已放通 `9602` 端口。

> WebSocket 断开后程序会**自动重连**（1s→2s→4s…最长 60s），网络恢复后无需人工干预。

### Q2：日志报「未配置MySQL数据源 / 连接池初始化失败」

1. 检查 `heg.mysql[].app-id` 与 `cloud.app-id` **完全一致**（大小写敏感）；
2. 数据库地址不能写 `localhost`，要用 `host.docker.internal` 或内网 IP；
3. 测试连通性（容器内无网络工具时可借助临时容器）：
   ```bash
   docker run --rm --network container:heg busybox nc -zv <数据库IP> 3306
   ```

### Q3：`docker load` 报错「invalid tar header」/ 文件损坏

确认上传过程完整（比对文件大小与 md5）：

```bash
md5sum hiasoon-edge-gateway-1.0.0.tar      # 与构建机输出对比
```

### Q4：端口 9000 被占用

`-p` 改为其它宿主机端口，例如 `-p 9001:9000`（容器内端口不变，保持 9000）。

### Q5：如何调整 JVM 内存

```bash
docker run -d ... -e JAVA_OPTS="-Xms512m -Xmx1024m -Dfile.encoding=UTF-8" hiasoon-edge-gateway:1.0.0
```

### Q6：修改配置后不生效

```bash
docker restart heg      # 配置通过挂载加载，需重启容器
docker inspect heg | grep -A 10 Mounts   # 确认挂载路径正确
```

### Q7：容器时间不对

镜像已默认 `TZ=Asia/Shanghai`；如仍不准，可挂载宿主机时区：

```bash
-v /etc/localtime:/etc/localtime:ro
```

---

## 六、配置项速查表

| 配置项 | 是否必填 | 默认值 | 说明 |
|--------|---------|--------|------|
| `server.port` | 否 | 9000 | 容器内服务端口 |
| `heg.cloud.websocket-url` | ✅ | — | 中台 HEG WebSocket 地址 |
| `heg.cloud.app-id` | ✅ | — | 应用 APPID |
| `heg.cloud.secret-key` | ✅ | — | 应用密钥 |
| `heg.cloud.api-base-url` | ✅ | — | 中台网关地址 |
| `heg.mysql[].app-id / url / username / password` | 按需 | — | 本地 MySQL（app-id 同 cloud.app-id） |
| `heg.mysql[].initial-size / max-active` | 否 | 5 / 20 | 连接池参数 |
| `heg.redis[].host / port / password / database` | 按需 | — / 6379 / — / 0 | 本地 Redis |
| `heg.mongodb[].uri / database` | 按需 | — | 本地 MongoDB |
| `heg.config-persistence.type` | 否 | file | `memory` / `file` |
| `heg.log-persistence.enabled` | 否 | false | SQL/转发日志是否落盘 |
| `logging.level.com.hiasoon.heg` | 否 | INFO | 日志级别（排查可调 DEBUG） |

---

## 七、技术支持

提交问题请附上：

1. `docker logs --tail 300 heg` 的完整输出；
2. `docker inspect heg` 中的 `Mounts` 与 `Env`；
3. 镜像版本（`docker images | grep hiasoon-edge-gateway`）；
4. 脱敏后的 `config/application.yml`（**请隐去 secret-key 与数据库密码**）。
