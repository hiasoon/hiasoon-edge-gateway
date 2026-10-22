#!/usr/bin/env bash

#############################################
# HEG 边缘网关 - 构建与打包脚本
#
# 用法：
#   ./heg.sh package    # Maven 打包，产物在 target/
#   ./heg.sh image      # 基于已打包 jar 构建镜像并导出 tar 包
#   ./heg.sh build      # package + image
#   ./heg.sh clean      # 清理 target 与镜像 tar 包
#   ./heg.sh help       # 显示帮助
#
# 环境要求：
#   package: JDK 17、Maven 3.6+
#   image  : Docker 20.10+（守护进程可用）
#
# 可选环境变量：
#   IMAGE_NAME  镜像名，默认 hiasoon-edge-gateway
#   IMAGE_TAG   镜像标签，默认读取 pom.xml 中的项目版本
#############################################

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

PROJECT_VERSION="$(
    grep -A1 '<artifactId>hiasoon-edge-gateway</artifactId>' "${SCRIPT_DIR}/pom.xml" 2>/dev/null \
        | sed -n 's:.*<version>\(.*\)</version>.*:\1:p' \
        | head -n1
)" || true

IMAGE_NAME="${IMAGE_NAME:-hiasoon-edge-gateway}"
IMAGE_TAG="${IMAGE_TAG:-${PROJECT_VERSION:-1.0.0}}"
IMAGE_REF="${IMAGE_NAME}:${IMAGE_TAG}"
IMAGE_TAR="${SCRIPT_DIR}/${IMAGE_NAME}-${IMAGE_TAG}.tar"

# ==========================================
# 日志输出
# ==========================================
info()  { printf '\033[32m[INFO ]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[WARN ]\033[0m %s\n' "$*"; }
error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*"; }

# ==========================================
# 帮助
# ==========================================
usage(){
    cat <<EOF
HEG 边缘网关 构建脚本

用法: ./heg.sh {package|image|build|clean}

  package   仅执行 Maven 打包，产物: target/*.jar
  image     基于已打包 jar 构建镜像并导出 tar 包
  build     package + image
  clean     清理 target/ 与镜像 tar 包

镜像: ${IMAGE_REF}
产物: $(basename "${IMAGE_TAR}")

可选环境变量:
  IMAGE_NAME  镜像名，默认 hiasoon-edge-gateway
  IMAGE_TAG   镜像标签，默认取自 pom.xml
EOF
}

# ==========================================
# 环境检查
# ==========================================
require_maven(){
    command -v java >/dev/null 2>&1 || { error "未找到 java，请先安装 JDK 17"; exit 1; }
    command -v mvn  >/dev/null 2>&1 || { error "未找到 mvn，请先安装 Maven 3.6+"; exit 1; }

    local major
    major="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F. '{print ($1 == "1" ? $2 : $1)}')"
    if [ -z "${major}" ] || [ "${major}" -lt 17 ]; then
        error "需要 JDK 17 及以上，当前: $(java -version 2>&1 | head -n1)"
        exit 1
    fi

    info "java  : $(java -version 2>&1 | head -n1)"
    info "maven : $(mvn -v 2>/dev/null | head -n1)"
}

require_docker(){
    command -v docker >/dev/null 2>&1 || { error "未找到 docker，请先安装 Docker 20.10+"; exit 1; }
    if ! docker info >/dev/null 2>&1; then
        error "无法连接 Docker 守护进程，请确认服务已启动且当前用户有权限"
        exit 1
    fi
    info "docker: $(docker -v)"
}

# ==========================================
# 查找 Maven 产物（排除 *.jar.original）
# ==========================================
find_jar(){
    find "${SCRIPT_DIR}/target" -maxdepth 1 -type f -name '*.jar' 2>/dev/null | head -n1 || true
}

#########################################
# 编译打包
#########################################
package(){
    require_maven

    info "==================== Maven 打包 ===================="
    mvn clean package -DskipTests -Dmaven.test.skip=true

    local jar
    jar="$(find_jar)"
    if [ -z "${jar}" ]; then
        error "打包失败：target 目录下未找到 jar"
        exit 1
    fi
    info "打包成功：${jar} ($(du -h "${jar}" | cut -f1))"
}

#########################################
# 构建镜像并导出 tar 包
#########################################
image(){
    require_docker

    local jar
    jar="$(find_jar)"
    if [ -z "${jar}" ]; then
        error "未找到 jar，请先执行：./heg.sh package"
        exit 1
    fi

    info "==================== 构建镜像 ===================="
    info "镜像名称：${IMAGE_REF}"
    docker build -t "${IMAGE_REF}" "${SCRIPT_DIR}"

    info "导出镜像 tar 包（不压缩）..."
    docker save -o "${IMAGE_TAR}" "${IMAGE_REF}"

    info "构建完成：${IMAGE_TAR} ($(du -h "${IMAGE_TAR}" | cut -f1))"

    cat <<EOF

上传 $(basename "${IMAGE_TAR}") 到目标服务器后执行：

  docker load -i $(basename "${IMAGE_TAR}")
  docker run -d --name heg --restart unless-stopped \\
    -p 9000:9000 \\
    -v /opt/heg/logs:/app/logs \\
    --add-host=host.docker.internal:host-gateway \\
    ${IMAGE_REF}

配置文件可通过 -v /opt/heg/config/application.yml:/app/config/application.yml 挂载，
或直接使用 docker-compose.yml 启动。详细说明见 DEPLOY.md。
EOF
}

########################################
# 清理构建产物
########################################
clean(){
    info "清理 Maven 构建产物 ..."
    rm -rf "${SCRIPT_DIR}/target"
    info "清理镜像 tar 包 ..."
    rm -f "${SCRIPT_DIR}/${IMAGE_NAME}-"*.tar
    info "清理完成"
}

case "${1:-}" in
    package)        package ;;
    image)          image ;;
    build)          package; image ;;
    clean)          clean ;;
    help|-h|--help) usage ;;
    *)              usage; exit 1 ;;
esac
