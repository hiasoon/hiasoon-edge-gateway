# 单阶段构建（jar已在本地构建好）
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="hiasoon.com"
LABEL description="Hiasoon Edge Gateway (HEG)"

# 安装curl用于健康检查
RUN apk add --no-cache curl

WORKDIR /app

# 复制 Maven 构建产物（由 heg.sh package 生成）
COPY target/*.jar app.jar

# 创建数据卷目录
RUN mkdir -p /app/data /app/logs/sql /app/logs/proxy

# 声明数据卷
VOLUME ["/app/data", "/app/logs"]

# 暴露端口
EXPOSE 9000

# 健康检查（项目未引入 actuator，使用应用自身的 /heg/test/health 端点）
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:9000/heg/test/health || exit 1

# JVM参数可通过环境变量调整
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
