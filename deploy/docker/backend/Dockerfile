# syntax=docker/dockerfile:1
# ---------- 构建阶段 ----------
# 项目 java.version=21；Milvus 3.0 客户端(milvus-sdk-java 3.0.13)随 forge-rag 打进胖 jar。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 多模块 reactor 有 40+ 个子 pom。旧写法只 COPY 6 个聚合 pom，dependency:go-offline
# 在解析 reactor 时就会失败并被 `|| true` 吞掉，缓存层实际从未生效；新增 19.5MB 的
# Milvus SDK 后每次构建都要全量重下。改用 BuildKit 的 Maven 仓库缓存挂载：
# 源码变化只重建编译层，/root/.m2 跨构建复用，且不依赖枚举所有子 pom。
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B clean package -DskipTests -Pprod -pl forge-admin -am

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="forgeagent-backend"

WORKDIR /app
# curl 供 compose 的 /actuator/health 探针使用(基础镜像 eclipse-temurin:21-jre 不自带,
# 缺了会让 backend 永远 unhealthy、frontend 的 service_healthy 依赖永不满足);
# /forge/server/temp 是 application-prod.yml 的 spring.servlet.multipart.location。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/data/knowledge /app/logs /forge/server/temp

COPY --from=build /build/forge-admin/target/forge-admin.jar /app/forge-admin.jar

# 中文日志/文件名编码固定，不随容器 LANG 漂移。JAVA_OPTS 由 compose 覆盖，所以
# -Dfile.encoding 写在 ENTRYPOINT 里，保证被覆盖后仍生效。
# -Xmx1g 与 compose 的 mem_limit 2g 之间的余量留给元空间、线程栈与 shaded
# gRPC/Netty 的直接内存(Milvus SDK 3.0.13 自带 shaded grpc，不走平台 io.grpc)。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-Xms512m -Xmx1g -XX:+UseZGC"

EXPOSE 8080

# exec 让 JVM 成为 PID 1，能收到 SIGTERM 走 Spring Boot 优雅停机(sh 不会转发信号)
ENTRYPOINT ["sh", "-c", "exec java -Dfile.encoding=UTF-8 $JAVA_OPTS -jar /app/forge-admin.jar"]
