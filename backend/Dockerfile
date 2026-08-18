# syntax=docker/dockerfile:1
# ==========================================================================
# Albedo 后端镜像（Dockerfile）
#
# 🔴 默认构建路径（推荐）：复用本机 mvn package 产物 target/albedo-backend.jar
#    原因：私有依赖 com.eyes:eyesAuth-spring-boot-starter:1.1.0 只存在于本机 ~/.m2
#    （无远程仓库配置），容器内无法从 Maven Central 解析。
#    因此先在本机构建 JAR，再打包进运行时镜像：
#        cd backend && mvn -q -DskipTests package
#        cd .. && docker build -t albedo-backend:1.0.0 backend/
#
# 🔴 完整源码构建路径（CI / 无本机产物场景）：
#    build 阶段通过 buildx 的 --build-context 把宿主机 ~/.m2 挂进构建器：
#        docker buildx build \
#          --build-context m2repo=$HOME/.m2/repository \
#          --target build \
#          -t albedo-build backend/
#    然后把 build 阶段产物手动拷出，或用 CI 的私有 Maven 仓库（届时删掉
#    --build-context，让 mvn 走远程仓库即可）。
# ==========================================================================

# ---------- 阶段 1：完整源码构建（CI 专用，需要 m2repo 构建上下文） ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN --mount=type=bind,from=m2repo,target=/root/.m2 \
    mvn -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN --mount=type=bind,from=m2repo,target=/root/.m2 \
    mvn -q -DskipTests package

# ---------- 阶段 2：运行时镜像（默认 target） ----------
FROM eclipse-temurin:17-jre

# 非 root 运行（容器内白名单原则）
RUN useradd -r -u 1001 albedo \
    && mkdir -p /app/logs \
    && chown -R albedo:albedo /app

WORKDIR /app

# 复用本机 mvn 产物（backend/target/albedo-backend.jar，.dockerignore 仅放行该文件）
COPY target/albedo-backend.jar app.jar

USER albedo

EXPOSE 8080

ENV JAVA_OPTS="-Xms512m -Xmx2g" \
    SPRING_PROFILES_ACTIVE=prod \
    LOG_PATH=/app/logs \
    TZ=Asia/Shanghai

# 🔴 -Dnetworkaddress.cache.ttl=10 是必需启动参数（ADR-009 SSRF 防护，StartupChecker 会 WARN）
#    -DLOG_PATH 覆盖 logback 落盘位置；日志目录挂载自 compose volume
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dnetworkaddress.cache.ttl=10 -DLOG_PATH=${LOG_PATH} -jar app.jar"]
